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

    @Test
    fun `abandon count appears in the no-findings summary`() {
        val out = ConsoleReporter.report(emptyList(), filesReviewed = 3, abandonedUnits = 2, useColor = false)
        assertTrue(out.contains("2 unit(s) abandoned"), out)
    }

    @Test
    fun `abandon count appears in the findings summary`() {
        val out = ConsoleReporter.report(listOf(finding), filesReviewed = 1, abandonedUnits = 1, useColor = false)
        assertTrue(out.contains("1 unit(s) abandoned"), out)
    }

    @Test
    fun `no abandon suffix when zero`() {
        assertFalse(
            ConsoleReporter.report(emptyList(), filesReviewed = 1, useColor = false).contains("abandoned"),
        )
        assertFalse(
            ConsoleReporter.report(listOf(finding), filesReviewed = 1, useColor = false).contains("abandoned"),
        )
    }
}
