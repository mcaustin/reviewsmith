package dev.reviewsmith.core

import dev.reviewsmith.spi.Confidence
import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GateEvaluatorTest {

    private fun finding(
        ruleId: String = "r",
        severity: Severity,
        confidence: Confidence?,
    ) = Finding(ruleId = ruleId, file = "A.kt", line = 1, severity = severity, message = "m", confidence = confidence)

    private fun rule(id: String = "r", tags: List<String>) =
        Rule(id, id, Severity.WARNING, listOf("**/*.kt"), emptyList(), tags, "body")

    private fun gate(
        failOn: String = "none",
        failOnCategory: List<String> = emptyList(),
    ) = GateConfig(failOn = failOn, failOnCategory = failOnCategory)

    private fun decide(
        finding: Finding,
        gate: GateConfig,
        rulesById: Map<String, Rule> = emptyMap(),
    ) = GateEvaluator.evaluate(listOf(finding), gate, rulesById).decision

    @Test
    fun `failOn none never gates even a clear error`() {
        assertTrue(decide(finding(severity = Severity.ERROR, confidence = Confidence.CLEAR), gate()) is GateDecision.Pass)
    }

    @Test
    fun `default config never gates`() {
        val f = finding(severity = Severity.ERROR, confidence = Confidence.CLEAR)
        assertTrue(GateEvaluator.evaluate(listOf(f), GateConfig()).decision is GateDecision.Pass)
    }

    @Test
    fun `failOn error gates a clear error`() {
        assertTrue(decide(finding(severity = Severity.ERROR, confidence = Confidence.CLEAR), gate("error")) is GateDecision.Fail)
    }

    @Test
    fun `failOn error does not gate a clear warning`() {
        assertTrue(decide(finding(severity = Severity.WARNING, confidence = Confidence.CLEAR), gate("error")) is GateDecision.Pass)
    }

    @Test
    fun `failOn warning gates a clear warning and a clear error`() {
        assertTrue(decide(finding(severity = Severity.WARNING, confidence = Confidence.CLEAR), gate("warning")) is GateDecision.Fail)
        assertTrue(decide(finding(severity = Severity.ERROR, confidence = Confidence.CLEAR), gate("warning")) is GateDecision.Fail)
    }

    @Test
    fun `ambiguous findings never gate`() {
        assertTrue(decide(finding(severity = Severity.ERROR, confidence = Confidence.AMBIGUOUS), gate("error")) is GateDecision.Pass)
    }

    @Test
    fun `null confidence never gates and emits a warning`() {
        val f = finding(severity = Severity.ERROR, confidence = null)
        val result = GateEvaluator.evaluate(listOf(f), gate("error"))
        assertTrue(result.decision is GateDecision.Pass)
        assertTrue(result.warnings.any { it.contains("confidence") }, "expected a null-confidence warning")
    }

    @Test
    fun `category arm gates a tagged clear finding regardless of severity`() {
        val f = finding(severity = Severity.INFO, confidence = Confidence.CLEAR)
        assertTrue(decide(f, gate(failOnCategory = listOf("safety")), mapOf("r" to rule(tags = listOf("safety")))) is GateDecision.Fail)
    }

    @Test
    fun `category arm ignores untagged rules`() {
        val f = finding(severity = Severity.INFO, confidence = Confidence.CLEAR)
        assertTrue(decide(f, gate(failOnCategory = listOf("safety")), mapOf("r" to rule(tags = listOf("auth")))) is GateDecision.Pass)
    }

    @Test
    fun `category arm ignores ambiguous findings`() {
        val f = finding(severity = Severity.INFO, confidence = Confidence.AMBIGUOUS)
        assertTrue(decide(f, gate(failOnCategory = listOf("safety")), mapOf("r" to rule(tags = listOf("safety")))) is GateDecision.Pass)
    }

    @Test
    fun `both arms triggering counts the finding once`() {
        val f = finding(severity = Severity.ERROR, confidence = Confidence.CLEAR)
        val decision = decide(f, gate("error", listOf("safety")), mapOf("r" to rule(tags = listOf("safety"))))
        assertTrue(decision is GateDecision.Fail)
        assertEquals(1, (decision as GateDecision.Fail).triggeringFindings.size)
    }

    @Test
    fun `validator-synthesized findings cannot be category-gated`() {
        val f = finding(ruleId = "reviewsmith", severity = Severity.ERROR, confidence = Confidence.CLEAR)
        assertTrue(decide(f, gate(failOnCategory = listOf("safety"))) is GateDecision.Pass)
    }

    @Test
    fun `onlyConfidence ambiguous gates an ambiguous error`() {
        val f = finding(severity = Severity.ERROR, confidence = Confidence.AMBIGUOUS)
        val g = GateConfig(failOn = "error", onlyConfidence = "ambiguous")
        assertTrue(decide(f, g) is GateDecision.Fail)
    }

    @Test
    fun `onlyConfidence ambiguous still gates a clear error`() {
        val f = finding(severity = Severity.ERROR, confidence = Confidence.CLEAR)
        val g = GateConfig(failOn = "error", onlyConfidence = "ambiguous")
        assertTrue(decide(f, g) is GateDecision.Fail)
    }

    @Test
    fun `default onlyConfidence does not gate an ambiguous error`() {
        val f = finding(severity = Severity.ERROR, confidence = Confidence.AMBIGUOUS)
        assertTrue(decide(f, gate("error")) is GateDecision.Pass)
    }

    @Test
    fun `empty findings pass`() {
        assertTrue(GateEvaluator.evaluate(emptyList(), gate("error")).decision is GateDecision.Pass)
    }

    @Test
    fun `no warning when gate inactive`() {
        val f = finding(severity = Severity.ERROR, confidence = null)
        assertTrue(GateEvaluator.evaluate(listOf(f), GateConfig()).warnings.isEmpty())
    }

    @Test
    fun `no warning when all findings are stamped`() {
        val f = finding(severity = Severity.ERROR, confidence = Confidence.CLEAR)
        assertTrue(GateEvaluator.evaluate(listOf(f), gate("error")).warnings.isEmpty())
    }
}
