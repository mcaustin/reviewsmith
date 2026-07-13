package dev.reviewsmith.core

import dev.reviewsmith.spi.AgentProvider
import dev.reviewsmith.spi.AgentRequest
import dev.reviewsmith.spi.Confidence
import dev.reviewsmith.spi.Finding
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.encodeToString
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thrown before any agent call when the resolved scope exceeds [ScopeConfig.maxUnits]. A
 * safety backstop against a mis-resolved base (e.g. a stale local branch) fanning out into a
 * whole-codebase review. The CLI maps this to a non-zero exit; `--force` bypasses the check.
 */
class ScopeExceededException(val units: Int, val maxUnits: Int) : RuntimeException(
    "Scope of $units work unit(s) exceeds maxUnits=$maxUnits. This usually means the diff base " +
        "resolved wrong (e.g. a stale local branch). Run with --dry-run to inspect the scope, narrow " +
        "scope.baseRef, or pass --force / raise scope.maxUnits to proceed.",
)

data class RunResult(
    val findings: List<Finding>,
    val filesReviewed: Int,
    val rulesRun: Int,
    val suppressedByBaseline: Int = 0,
    val suppressedInline: Int = 0,
    val abandonedUnits: Int = 0,
    val totalCostUsd: Double? = null,
    val cacheHits: Int = 0,
    val modelId: String? = null,
    val rulesById: Map<String, Rule> = emptyMap(),
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
        config: ReviewsmithConfig? = null,
    ): RunResult {
        @Suppress("NAME_SHADOWING")
        val config = config ?: ReviewsmithConfig.load(repoRoot)
        val effectiveMode = mode ?: config.scope.default
        val files = scopeResolver.resolve(repoRoot, config, effectiveMode)
        val docs = DocContextBuilder.discover(repoRoot, config.docs)
        val rules = RuleResolver.resolve(repoRoot, config)
        val rulesById = rules.associateBy { it.id }
        val model = provider.effectiveModel

        if (files.isEmpty()) {
            return RunResult(emptyList(), 0, rules.size, modelId = model, rulesById = rulesById)
        }

        val diffs = if (config.scope.includeDiff) {
            scopeResolver.captureDiffs(repoRoot, config, files)
        } else {
            DiffContext.EMPTY
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

        System.err.println(
            "Reviewsmith: ${files.size} file(s), ${rules.size} rule(s) → ${units.size} work unit(s). " +
                "Estimated cost $%.2f–$%.2f.".format(units.size * 0.15, units.size * 0.50),
        )
        val maxUnits = config.scope.maxUnits
        if (maxUnits > 0 && units.size > maxUnits) {
            throw ScopeExceededException(units.size, maxUnits)
        }

        val stats = ConcurrentHashMap<String, RuleStat>()
        val verbose = System.getenv("REVIEWSMITH_VERBOSE") == "1"

        val runUnit: suspend (Pair<Rule, String>) -> List<Finding> = { (rule, file) ->
            val fileDiff = diffs.forFile(file).orEmpty()
            val key = if (cache != null && model != null) {
                CacheKeyBuilder.build(rule, file, docs, repoRoot, model, provider.allowedTools, fileDiff)
            } else {
                null
            }
            val cached = key?.let { cache!!.lookup(it) }
            if (cached != null) {
                recordStat(stats, rule.id, durationMs = 0, costUsd = 0.0, isHit = true)
                if (verbose) {
                    System.err.println("Reviewsmith: [${rule.id} @ ${file}] cache hit")
                }
                cached
            } else {
                val ruleDocs = (docs + rule.docs).distinct()
                val request = AgentRequest(
                    systemPrompt = Prompts.ruleSystemPrompt(),
                    rulePrompt = Prompts.ruleUserPrompt(rule, listOf(file), ruleDocs, fileDiff),
                    targetFiles = listOf(file),
                    docRefs = ruleDocs,
                    projectRoot = repoRoot.toString(),
                    outputSchema = Prompts.findingsSchema,
                    callTimeoutSeconds = rule.callTimeoutSeconds ?: config.callTimeoutSeconds,
                    maxBudgetUsd = rule.maxBudgetUsd,
                )
                val result = provider.analyze(request)
                recordStat(stats, rule.id, result.durationMs, result.costUsd, isHit = false)
                if (verbose) {
                    val ms = result.durationMs?.let { "${it}ms" } ?: "?ms"
                    val cost = result.costUsd?.let { "$%.4f".format(it) } ?: "?"
                    System.err.println("Reviewsmith: [${rule.id} @ ${file}] $ms $cost")
                }
                val stamped = result.findings.map { it.copy(ruleId = rule.id) }
                if (key != null) cache!!.put(key, rule.id, file, stamped)
                stamped
            }
        }

        val spentUsd = { stats.values.sumOf { it.totalCost } }
        val streamed = java.util.concurrent.ConcurrentLinkedQueue<Finding>()
        val emit: (List<Finding>) -> Unit = { validated ->
            validated.forEach { f ->
                streamed.add(f)
                val loc = f.line?.let { ":$it" } ?: ""
                System.err.println("Reviewsmith: [preview] ${f.severity} ${f.ruleId} ${f.file}$loc — ${f.message}")
            }
        }
        val flushHook = Thread {
            if (streamed.isNotEmpty()) {
                System.err.println("Reviewsmith: interrupted — ${streamed.size} finding(s) collected before exit:")
                streamed.forEach { f ->
                    val loc = f.line?.let { ":$it" } ?: ""
                    System.err.println("  ${f.severity} ${f.ruleId} ${f.file}$loc — ${f.message}")
                }
            }
        }
        Runtime.getRuntime().addShutdownHook(flushHook)
        val pipelined = try {
            runPipelined(config, docs, repoRoot, units, diffs, verbose, spentUsd, emit, runUnit)
        } finally {
            runCatching { Runtime.getRuntime().removeShutdownHook(flushHook) }
        }

        cache?.pruneIfNeeded()
        printTimingSummary(stats)

        val afterInline = applyInlineSuppression(pipelined.findings, files, repoRoot, config.suppression)

        val partition = BaselineFilter.partition(afterInline.surfaced, store)
        return RunResult(
            findings = partition.surfaced,
            filesReviewed = files.size,
            rulesRun = rules.size,
            suppressedByBaseline = partition.suppressed.size,
            suppressedInline = afterInline.suppressed.size,
            abandonedUnits = pipelined.abandonedUnits,
            totalCostUsd = stats.values.sumOf { it.totalCost },
            cacheHits = stats.values.sumOf { it.hits },
            modelId = model,
            rulesById = rulesById,
        )
    }

    private fun applyInlineSuppression(
        findings: List<Finding>,
        files: List<String>,
        repoRoot: Path,
        config: SuppressionConfig,
    ): SuppressionResult {
        if (!config.enabled || findings.isEmpty()) {
            return SuppressionResult(findings, emptyList(), emptyList())
        }
        val byFile = files.associate { rel ->
            val text = runCatching { java.nio.file.Files.readString(repoRoot.resolve(rel)) }.getOrDefault("")
            Fingerprint.normalizeFile(rel) to SuppressionScanner.scan(rel, text)
        }
        val result = SuppressionFilter.apply(findings, byFile, config.band.coerceAtLeast(0))
        result.notices.forEach { System.err.println(it) }
        return result
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
     * Runs every (rule × file) unit with at most [ReviewsmithConfig.maxConcurrency] in flight
     * and, when the validator is enabled, streams each unit's findings into validator chunks
     * as they complete — so validation of early findings overlaps the slow rule tail instead
     * of waiting for a phase barrier. A unit's failure is isolated (logged, 0 findings,
     * counted); an unavailable agent is fatal and rethrown. Validator chunks and rule units
     * share one semaphore. Chunk count matches `findings.chunked(chunkSize)`: one call per
     * full chunk plus a final flush of the remainder.
     */
    private fun runPipelined(
        config: ReviewsmithConfig,
        docs: List<String>,
        repoRoot: Path,
        units: List<Pair<Rule, String>>,
        diffs: DiffContext,
        verbose: Boolean,
        spentUsd: () -> Double,
        emit: (List<Finding>) -> Unit,
        runUnit: suspend (Pair<Rule, String>) -> List<Finding>,
    ): BoundedRun = runBlocking(Dispatchers.IO) {
        val concurrency = config.maxConcurrency.coerceAtLeast(1)
        val semaphore = Semaphore(concurrency)
        val abandoned = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val total = units.size
        val validatorEnabled = config.validator.enabled
        val chunkSize = config.validator.chunkSize.coerceAtLeast(1)
        val budgetCap = config.maxTotalBudgetUsd
        val budgetTripped = java.util.concurrent.atomic.AtomicBoolean(false)

        coroutineScope {
            val channel = Channel<List<Finding>>(Channel.UNLIMITED)
            val validatorJobs = mutableListOf<Deferred<List<Finding>>>()

            val consumer = launch {
                val buffer = mutableListOf<Finding>()
                suspend fun flush() {
                    if (buffer.isEmpty()) return
                    val chunk = buffer.toList()
                    buffer.clear()
                    validatorJobs += async {
                        semaphore.withPermit {
                            validateChunk(repoRoot, chunk, docs, diffs, config.validator.timeoutSeconds)
                        }.also(emit)
                    }
                }
                for (produced in channel) {
                    if (!validatorEnabled) {
                        buffer += produced
                        continue
                    }
                    buffer += produced
                    while (buffer.size >= chunkSize) {
                        val chunk = buffer.take(chunkSize)
                        buffer.subList(0, chunkSize).clear()
                        validatorJobs += async {
                            semaphore.withPermit {
                                validateChunk(repoRoot, chunk, docs, diffs, config.validator.timeoutSeconds)
                            }.also(emit)
                        }
                    }
                }
                if (validatorEnabled) flush() else if (buffer.isNotEmpty()) {
                    val passthrough = buffer.toList()
                    validatorJobs += async { passthrough.also(emit) }
                }
            }

            val producers = units.map { unit ->
                launch {
                    semaphore.withPermit {
                        if (budgetCap != null && (budgetTripped.get() || spentUsd() >= budgetCap)) {
                            if (budgetTripped.compareAndSet(false, true)) {
                                System.err.println(
                                    "Reviewsmith: total budget cap of $%.2f reached (spent $%.4f) — ".format(budgetCap, spentUsd()) +
                                        "halting; remaining unit(s) abandoned.",
                                )
                            }
                            abandoned.incrementAndGet()
                            return@withPermit
                        }
                        val produced = try {
                            runUnit(unit)
                        } catch (e: Exception) {
                            if (isAgentUnavailable(e)) throw e
                            abandoned.incrementAndGet()
                            System.err.println("Reviewsmith: skipping a unit after ${abandonKind(e)}: ${e.message}")
                            emptyList()
                        }
                        if (!verbose) {
                            val (rule, file) = unit
                            System.err.println(
                                "Reviewsmith: [${completed.incrementAndGet()}/$total] ${rule.id} @ ${file}",
                            )
                        }
                        if (produced.isNotEmpty()) channel.send(produced)
                    }
                }
            }
            producers.joinAll()
            channel.close()
            consumer.join()

            BoundedRun(validatorJobs.awaitAll().flatten(), abandoned.get())
        }
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

    private fun validateChunk(
        repoRoot: Path,
        chunk: List<Finding>,
        docs: List<String>,
        diffs: DiffContext,
        timeoutSeconds: Long,
    ): List<Finding> {
        val rawJson = json.encodeToString(mapOf("findings" to chunk))
        val chunkDiff = diffs.forFiles(chunk.map { it.file })
        val request = AgentRequest(
            systemPrompt = Prompts.validatorSystemPrompt(),
            rulePrompt = Prompts.validatorUserPrompt(rawJson, docs, chunkDiff),
            targetFiles = chunk.map { it.file }.distinct(),
            docRefs = docs,
            projectRoot = repoRoot.toString(),
            outputSchema = Prompts.validatorSchema,
            callTimeoutSeconds = timeoutSeconds,
        )
        val validIds = chunk.map { it.ruleId }.filter { it.isNotBlank() }.toSet()
        val idByLocation = chunk
            .groupBy { it.file to it.line }
            .filterValues { it.map { f -> f.ruleId }.toSet().size == 1 }
            .mapValues { it.value.first().ruleId }
        return try {
            provider.analyze(request).findings.map { f ->
                val resolvedId = when {
                    f.ruleId in validIds -> f.ruleId
                    idByLocation[f.file to f.line] != null -> idByLocation.getValue(f.file to f.line)
                    else -> "reviewsmith"
                }
                if (resolvedId == f.ruleId) f else f.copy(ruleId = resolvedId)
            }
        } catch (e: Exception) {
            if (isAgentUnavailable(e)) throw e
            System.err.println(
                "Reviewsmith: validator chunk abandoned after ${abandonKind(e)}: ${e.message}; " +
                    "keeping ${chunk.size} finding(s) as AMBIGUOUS",
            )
            chunk.map { if (it.confidence == null) it.copy(confidence = Confidence.AMBIGUOUS) else it }
        }
    }
}
