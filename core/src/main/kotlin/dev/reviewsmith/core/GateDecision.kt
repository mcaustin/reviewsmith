package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding

sealed class GateDecision {
    object Pass : GateDecision()
    data class Fail(val triggeringFindings: List<Finding>) : GateDecision()
}

data class GateEvaluationResult(
    val decision: GateDecision,
    val warnings: List<String> = emptyList(),
)
