package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConsoleReporterTest {

    private val finding = Finding(
        ruleId = "r", file = "A.kt", line = 1, severity = Severity.WARNING, message = "m",
    )

    private val reporter = ConsoleReporter(useColor = false)

    private fun result(findings: List<Finding>, filesReviewed: Int, abandonedUnits: Int = 0) =
        RunResult(findings, filesReviewed, rulesRun = 1, abandonedUnits = abandonedUnits)

    @Test
    fun `abandon count appears in the no-findings summary`() {
        val out = reporter.report(result(emptyList(), filesReviewed = 3, abandonedUnits = 2))
        assertTrue(out.contains("2 unit(s) abandoned"), out)
    }

    @Test
    fun `abandon count appears in the findings summary`() {
        val out = reporter.report(result(listOf(finding), filesReviewed = 1, abandonedUnits = 1))
        assertTrue(out.contains("1 unit(s) abandoned"), out)
    }

    @Test
    fun `no abandon suffix when zero`() {
        assertFalse(reporter.report(result(emptyList(), filesReviewed = 1)).contains("abandoned"))
        assertFalse(reporter.report(result(listOf(finding), filesReviewed = 1)).contains("abandoned"))
    }

    @Test
    fun `suggested fix is rendered when present`() {
        val withFix = finding.copy(suggestedFix = "use coerceAtMost(cap)")
        val out = reporter.report(result(listOf(withFix), filesReviewed = 1))
        assertTrue(out.contains("fix: use coerceAtMost(cap)"), out)
    }

    @Test
    fun `no fix line when suggestedFix is absent`() {
        val out = reporter.report(result(listOf(finding), filesReviewed = 1))
        assertFalse(out.contains("fix:"), out)
    }
}
