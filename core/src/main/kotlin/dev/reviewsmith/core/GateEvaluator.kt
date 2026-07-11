package dev.reviewsmith.core

import dev.reviewsmith.spi.Confidence
import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity

object GateEvaluator {
    fun evaluate(
        findings: List<Finding>,
        config: GateConfig,
        rulesById: Map<String, Rule> = emptyMap(),
    ): GateEvaluationResult {
        val level = config.failOnLevel()
        val isActive = level != FailOnLevel.NONE || config.failOnCategory.isNotEmpty()
        if (!isActive) return GateEvaluationResult(GateDecision.Pass)

        val warnings = mutableListOf<String>()
        val nullConfCount = findings.count { it.confidence == null }
        if (nullConfCount > 0) {
            warnings += "Reviewsmith: WARNING: gate is active but $nullConfCount finding(s) have no " +
                "confidence stamp (validator may have been disabled or failed); those findings did not gate."
        }

        val gatedConfidences = config.gatedConfidences()
        val triggering = findings.filter { isGating(it, level, config.failOnCategory, rulesById, gatedConfidences) }
        val decision = if (triggering.isEmpty()) GateDecision.Pass else GateDecision.Fail(triggering)
        return GateEvaluationResult(decision, warnings)
    }

    private fun isGating(
        f: Finding,
        level: FailOnLevel,
        failOnCategory: List<String>,
        rulesById: Map<String, Rule>,
        gatedConfidences: Set<Confidence>,
    ): Boolean {
        if (f.confidence == null || f.confidence !in gatedConfidences) return false
        val thresholdTrigger = when (level) {
            FailOnLevel.NONE -> false
            FailOnLevel.WARNING -> f.severity == Severity.WARNING || f.severity == Severity.ERROR
            FailOnLevel.ERROR -> f.severity == Severity.ERROR
        }
        val categoryTrigger = failOnCategory.isNotEmpty() &&
            rulesById[f.ruleId]?.tags?.any { it in failOnCategory } == true
        return thresholdTrigger || categoryTrigger
    }
}
