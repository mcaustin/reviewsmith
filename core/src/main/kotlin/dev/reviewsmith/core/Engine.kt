package dev.reviewsmith.core

import dev.reviewsmith.spi.AgentProvider
import dev.reviewsmith.spi.AgentRequest
import dev.reviewsmith.spi.Finding
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.nio.file.Path

data class RunResult(
    val findings: List<Finding>,
    val filesReviewed: Int,
    val rulesRun: Int,
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
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    fun run(repoRoot: Path, mode: String? = null): RunResult {
        val config = ReviewsmithConfig.load(repoRoot)
        val effectiveMode = mode ?: config.scope.default
        val files = scopeResolver.resolve(repoRoot, config, effectiveMode)
        val docs = DocContextBuilder.discover(repoRoot, config.docs)
        val rules = RuleLoader.loadBundled()

        if (files.isEmpty()) {
            return RunResult(emptyList(), 0, rules.size)
        }

        val raw = mutableListOf<Finding>()
        for (rule in rules) {
            val targets = files.filter { rel -> matchesRule(rule, rel, config) }
            if (targets.isEmpty()) continue
            val request = AgentRequest(
                systemPrompt = Prompts.ruleSystemPrompt(),
                rulePrompt = Prompts.ruleUserPrompt(rule, targets, docs),
                targetFiles = targets,
                docRefs = docs,
                projectRoot = repoRoot.toString(),
                outputSchema = Prompts.findingsSchema,
            )
            val result = provider.analyze(request)
            // Stamp the rule id onto findings the agent produced (the rule schema has none).
            raw += result.findings.map { it.copy(ruleId = rule.id) }
        }

        val validated = if (config.validator.enabled && raw.isNotEmpty()) {
            validate(repoRoot, raw, docs)
        } else {
            raw
        }

        return RunResult(validated, files.size, rules.size)
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
