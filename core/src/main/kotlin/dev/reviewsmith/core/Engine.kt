package dev.reviewsmith.core

import dev.reviewsmith.spi.AgentProvider
import dev.reviewsmith.spi.AgentRequest
import dev.reviewsmith.spi.Finding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class RunResult(
    val findings: List<Finding>,
    val filesReviewed: Int,
    val rulesRun: Int,
    val suppressedByBaseline: Int = 0,
    val abandonedUnits: Int = 0,
    val totalCostUsd: Double? = null,
    val cacheHits: Int = 0,
)

/**
 * The minimal review pipeline: resolve scope + docs, run each rule via the provider,
 * then a single skeptical validator pass. Advisory only — never fails a build in this
 * milestone.
 */
class Engine(
    private val provider: AgentProvider,
    private val scopeResolver: ScopeResolver = ScopeResolver(),
) {
    private val json = reviewsmithJson

    fun run(
        repoRoot: Path,
        mode: String? = null,
        baselineStore: BaselineStore? = null,
        cacheStore: CacheStore? = null,
    ): RunResult {
        val config = ReviewsmithConfig.load(repoRoot)
        val effectiveMode = mode ?: config.scope.default
        val files = scopeResolver.resolve(repoRoot, config, effectiveMode)
        val docs = DocContextBuilder.discover(repoRoot, config.docs)
        val rules = RuleResolver.resolve(repoRoot, config)

        if (files.isEmpty()) {
            return RunResult(emptyList(), 0, rules.size)
        }

        val cache = cacheStore ?: resolveCache(config.cache, repoRoot)

        val store = baselineStore ?: if (config.baseline.enabled) {
            BaselineStore.resolveFromConfig(config, repoRoot)
        } else {
            BaselineStore.empty()
        }

        // One work unit per (rule × matching file), dispatched concurrently.
        val units = rules.flatMap { rule ->
            files.filter { rel -> matchesRule(rule, rel, config) }
                .map { file -> rule to file }
        }

        val stats = ConcurrentHashMap<String, RuleStat>()
        val verbose = System.getenv("REVIEWSMITH_VERBOSE") == "1"

        val model = provider.effectiveModel
        val ruleRun = runBounded(config.maxConcurrency, units) { (rule, file) ->
            val key = if (cache != null && model != null) {
                CacheKeyBuilder.build(rule, file, docs, repoRoot, model, provider.allowedTools)
            } else {
                null
            }
            val cached = key?.let { cache!!.lookup(it) }
            if (cached != null) {
                recordStat(stats, rule.id, durationMs = 0, costUsd = 0.0, isHit = true)
                if (verbose) {
                    System.err.println("Reviewsmith: [${rule.id} @ ${file.substringAfterLast('/')}] cache hit")
                }
                return@runBounded cached
            }

            val request = AgentRequest(
                systemPrompt = Prompts.ruleSystemPrompt(),
                rulePrompt = Prompts.ruleUserPrompt(rule, listOf(file), docs),
                targetFiles = listOf(file),
                docRefs = docs,
                projectRoot = repoRoot.toString(),
                outputSchema = Prompts.findingsSchema,
                callTimeoutSeconds = config.callTimeoutSeconds,
                maxBudgetUsd = rule.maxBudgetUsd,
            )
            val result = provider.analyze(request)
            recordStat(stats, rule.id, result.durationMs, result.costUsd, isHit = false)
            if (verbose) {
                val ms = result.durationMs?.let { "${it}ms" } ?: "?ms"
                val cost = result.costUsd?.let { "$%.4f".format(it) } ?: "?"
                System.err.println("Reviewsmith: [${rule.id} @ ${file.substringAfterLast('/')}] $ms $cost")
            }
            val stamped = result.findings.map { it.copy(ruleId = rule.id) }
            if (key != null) cache!!.put(key, rule.id, file, stamped)
            stamped
        }

        cache?.pruneIfNeeded()
        printTimingSummary(stats)

        val validated = if (config.validator.enabled && ruleRun.findings.isNotEmpty()) {
            validate(repoRoot, ruleRun.findings, docs, config.callTimeoutSeconds)
        } else {
            ruleRun.findings
        }

        val partition = BaselineFilter.partition(validated, store)
        return RunResult(
            findings = partition.surfaced,
            filesReviewed = files.size,
            rulesRun = rules.size,
            suppressedByBaseline = partition.suppressed.size,
            abandonedUnits = ruleRun.abandonedUnits,
            totalCostUsd = stats.values.sumOf { it.totalCost },
            cacheHits = stats.values.sumOf { it.hits },
        )
    }

    private fun resolveCache(config: CacheConfig, repoRoot: Path): CacheStore? {
        if (!config.enabled) return null
        if (provider.effectiveModel == null) {
            System.err.println("Reviewsmith: cache requires --model to be set; running without cache.")
            return null
        }
        return CacheStore.forConfig(config, repoRoot)
    }

    private data class BoundedRun(val findings: List<Finding>, val abandonedUnits: Int)

    private data class RuleStat(val maxMs: Long, val totalCost: Double, val units: Int, val hits: Int)

    private fun recordStat(
        stats: ConcurrentHashMap<String, RuleStat>,
        ruleId: String,
        durationMs: Long?,
        costUsd: Double?,
        isHit: Boolean,
    ) {
        val add = RuleStat(durationMs ?: 0, costUsd ?: 0.0, 1, if (isHit) 1 else 0)
        stats.merge(ruleId, add) { a, b ->
            RuleStat(maxOf(a.maxMs, b.maxMs), a.totalCost + b.totalCost, a.units + b.units, a.hits + b.hits)
        }
    }

    private fun printTimingSummary(stats: ConcurrentHashMap<String, RuleStat>) {
        if (stats.isEmpty()) return
        System.err.println("Reviewsmith: timing summary (max wall-clock per rule, total cost):")
        stats.entries.sortedByDescending { it.value.maxMs }.forEach { (ruleId, s) ->
            val hitSuffix = if (s.hits > 0) "  [cache hit: ${s.hits}]" else ""
            System.err.println(
                "  %-32s %7dms max / %d unit(s)  $%.4f".format(ruleId, s.maxMs, s.units, s.totalCost) + hitSuffix,
            )
        }
        val totalCost = stats.values.sumOf { it.totalCost }
        System.err.println("  %-32s %20s  $%.4f".format("total", "", totalCost))
    }

    /**
     * Runs [task] over [items] with at most [concurrency] in flight and flattens the
     * per-item finding lists. One item's failure is isolated (logged, contributes no
     * findings) so a single unreadable file can't sink the whole run — except an
     * unavailable agent, which is fatal for every item and is rethrown.
     */
    private fun <T> runBounded(
        concurrency: Int,
        items: List<T>,
        task: suspend (T) -> List<Finding>,
    ): BoundedRun = runBlocking(Dispatchers.IO) {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        val abandoned = AtomicInteger(0)
        val findings = coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        try {
                            task(item)
                        } catch (e: Exception) {
                            if (isAgentUnavailable(e)) throw e
                            abandoned.incrementAndGet()
                            System.err.println("Reviewsmith: skipping a unit after ${abandonKind(e)}: ${e.message}")
                            emptyList()
                        }
                    }
                }
            }.awaitAll().flatten()
        }
        BoundedRun(findings, abandoned.get())
    }

    private fun abandonKind(e: Throwable): String = when (e::class.simpleName) {
        "AgentTimeoutException" -> "timeout"
        "AgentBudgetExceededException" -> "budget cap"
        else -> "error"
    }

    private fun isAgentUnavailable(e: Throwable): Boolean =
        generateSequence(e) { it.cause }.any {
            it::class.simpleName == "AgentUnavailableException"
        }

    private fun matchesRule(rule: Rule, rel: String, config: ReviewsmithConfig): Boolean {
        if (rule.appliesTo.isEmpty()) return true
        val matchers = rule.appliesTo.map { GlobUtil.matcher(it) }
        return matchers.any { it(rel) }
    }

    private fun validate(
        repoRoot: Path,
        raw: List<Finding>,
        docs: List<String>,
        timeoutSeconds: Long,
    ): List<Finding> {
        val rawJson = json.encodeToString(mapOf("findings" to raw))
        val request = AgentRequest(
            systemPrompt = Prompts.validatorSystemPrompt(),
            rulePrompt = Prompts.validatorUserPrompt(rawJson, docs),
            targetFiles = raw.map { it.file }.distinct(),
            docRefs = docs,
            projectRoot = repoRoot.toString(),
            outputSchema = Prompts.validatorSchema,
            callTimeoutSeconds = timeoutSeconds,
        )
        return try {
            provider.analyze(request).findings.map {
                if (it.ruleId.isBlank()) it.copy(ruleId = "reviewsmith") else it
            }
        } catch (e: Exception) {
            if (isAgentUnavailable(e)) throw e
            System.err.println(
                "Reviewsmith: validator abandoned after ${abandonKind(e)}: ${e.message}; keeping raw findings",
            )
            raw
        }
    }
}
