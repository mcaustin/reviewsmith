package dev.reviewsmith.core

import dev.reviewsmith.spi.AgentProvider
import dev.reviewsmith.spi.AgentRequest
import dev.reviewsmith.spi.AgentResult
import dev.reviewsmith.spi.Finding
import java.util.Collections

/**
 * A deterministic [AgentProvider] for engine tests. Records every request and returns
 * canned findings; the validator pass (a request whose target set spans multiple files or
 * whose system prompt is the validator's) echoes back whatever it was given.
 */
class FakeProvider(
    private val findingsPerRuleCall: List<Finding> = emptyList(),
    private val durationMs: Long? = null,
    private val costUsd: Double? = null,
    model: String = "fake-model",
) : AgentProvider {
    override val id: String = "fake"
    override val effectiveModel: String = model
    override val allowedTools: String = "Read,Grep,Glob"

    val requests: MutableList<AgentRequest> = Collections.synchronizedList(mutableListOf())

    override fun analyze(request: AgentRequest): AgentResult {
        requests.add(request)
        // The validator prompt embeds a findings JSON blob; detect it and pass through.
        val isValidator = request.systemPrompt.contains("skeptical", ignoreCase = true) ||
            request.rulePrompt.contains("\"findings\"")
        val findings = if (isValidator) emptyList() else findingsPerRuleCall
        return AgentResult(
            findings = findings,
            modelId = "fake-model",
            durationMs = durationMs,
            costUsd = costUsd,
        )
    }

    fun ruleCallCount(): Int = requests.count {
        !(it.systemPrompt.contains("skeptical", ignoreCase = true) ||
            it.rulePrompt.contains("\"findings\""))
    }
}
