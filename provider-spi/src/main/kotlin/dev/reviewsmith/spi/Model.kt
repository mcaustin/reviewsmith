package dev.reviewsmith.spi

import kotlinx.serialization.Serializable

@Serializable
enum class Severity { INFO, WARNING, ERROR }

@Serializable
enum class Confidence { CLEAR, AMBIGUOUS }

@Serializable
data class Finding(
    val ruleId: String,
    val file: String,
    val line: Int? = null,
    val severity: Severity,
    val message: String,
    val rationale: String? = null,
    val confidence: Confidence? = null,
    val suggestedFix: String? = null,
)

data class TokenUsage(
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,
    val cacheReadInputTokens: Long? = null,
)

data class AgentRequest(
    val systemPrompt: String,
    val rulePrompt: String,
    val targetFiles: List<String>,
    val docRefs: List<String>,
    val projectRoot: String,
    val outputSchema: String,
    val callTimeoutSeconds: Long = 300,
    val maxBudgetUsd: Double? = null,
)

data class AgentResult(
    val findings: List<Finding>,
    val modelId: String? = null,
    val usage: TokenUsage? = null,
    val rawOutput: String? = null,
    val durationMs: Long? = null,
    val costUsd: Double? = null,
)

/**
 * Turns one analysis request into structured findings. The engine owns the output
 * contract (a JSON findings schema); a provider is responsible for coercing its agent
 * into it.
 */
interface AgentProvider {
    val id: String

    /** The resolved model id, or null when unset (the cache is disabled without it). */
    val effectiveModel: String? get() = null

    /** The tool names the agent may use, as passed to the agent (affects what it can observe). */
    val allowedTools: String get() = ""

    fun analyze(request: AgentRequest): AgentResult
}
