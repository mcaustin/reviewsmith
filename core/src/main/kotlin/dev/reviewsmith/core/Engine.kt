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

data class RunResult(
    val findings: List<Finding>,
    val filesReviewed: Int,
    val rulesRun: Int,
    val suppressedByBaseline: Int = 0,
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

    fun run(repoRoot: Path, mode: String? = null, baselineStore: BaselineStore? = null): RunResult {
        val config = ReviewsmithConfig.load(repoRoot)
        val effectiveMode = mode ?: config.scope.default
        val files = scopeResolver.resolve(repoRoot, config, effectiveMode)
        val docs = DocContextBuilder.discover(repoRoot, config.docs)
        val rules = RuleResolver.resolve(repoRoot, config)

        if (files.isEmpty()) {
            return RunResult(emptyList(), 0, rules.size)
        }

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

        val raw = runBounded(config.maxConcurrency, units) { (rule, file) ->
            val request = AgentRequest(
                systemPrompt = Prompts.ruleSystemPrompt(),
                rulePrompt = Prompts.ruleUserPrompt(rule, listOf(file), docs),
                targetFiles = listOf(file),
                docRefs = docs,
                projectRoot = repoRoot.toString(),
                outputSchema = Prompts.findingsSchema,
            )
            provider.analyze(request).findings.map { it.copy(ruleId = rule.id) }
        }

        val validated = if (config.validator.enabled && raw.isNotEmpty()) {
            validate(repoRoot, raw, docs)
        } else {
            raw
        }

        val partition = BaselineFilter.partition(validated, store)
        return RunResult(partition.surfaced, files.size, rules.size, partition.suppressed.size)
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
    ): List<Finding> = runBlocking(Dispatchers.IO) {
        val semaphore = Semaphore(concurrency.coerceAtLeast(1))
        coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        try {
                            task(item)
                        } catch (e: Exception) {
                            if (isAgentUnavailable(e)) throw e
                            System.err.println("Reviewsmith: skipping a unit after error: ${e.message}")
                            emptyList()
                        }
                    }
                }
            }.awaitAll().flatten()
        }
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

    private fun validate(repoRoot: Path, raw: List<Finding>, docs: List<String>): List<Finding> {
        val rawJson = json.encodeToString(mapOf("findings" to raw))
        val request = AgentRequest(
            systemPrompt = Prompts.validatorSystemPrompt(),
            rulePrompt = Prompts.validatorUserPrompt(rawJson, docs),
            targetFiles = raw.map { it.file }.distinct(),
            docRefs = docs,
            projectRoot = repoRoot.toString(),
            outputSchema = Prompts.validatorSchema,
        )
        val result = provider.analyze(request)
        return result.findings.map {
            if (it.ruleId.isBlank()) it.copy(ruleId = "reviewsmith") else it
        }
    }
}
