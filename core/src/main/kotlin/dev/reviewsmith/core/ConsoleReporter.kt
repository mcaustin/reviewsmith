package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity

object ConsoleReporter {
    private val ESC = "\u001B"
    private val RESET = "$ESC[0m"
    private val RED = "$ESC[31m"
    private val YELLOW = "$ESC[33m"
    private val BLUE = "$ESC[34m"
    private val BOLD = "$ESC[1m"
    private val DIM = "$ESC[2m"

    fun report(findings: List<Finding>, filesReviewed: Int, useColor: Boolean = true): String {
        fun c(code: String) = if (useColor) code else ""
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("${c(BOLD)}Reviewsmith${c(RESET)} — reviewed $filesReviewed file(s)")
        sb.appendLine()

        if (findings.isEmpty()) {
            sb.appendLine("${c(BLUE)}No findings.${c(RESET)}")
            return sb.toString()
        }

        for (f in findings.sortedWith(compareBy({ severityRank(it.severity) }, { it.file }))) {
            val color = when (f.severity) {
                Severity.ERROR -> c(RED)
                Severity.WARNING -> c(YELLOW)
                Severity.INFO -> c(BLUE)
            }
            val loc = f.line?.let { ":$it" } ?: ""
            val conf = f.confidence?.let { " ${c(DIM)}[${it.name.lowercase()}]${c(RESET)}" } ?: ""
            sb.appendLine("$color${f.severity}${c(RESET)} ${c(BOLD)}${f.file}$loc${c(RESET)}$conf")
            sb.appendLine("  ${f.message}  ${c(DIM)}(${f.ruleId})${c(RESET)}")
            f.rationale?.takeIf { it.isNotBlank() }?.let {
                sb.appendLine("  ${c(DIM)}$it${c(RESET)}")
            }
            sb.appendLine()
        }

        val errors = findings.count { it.severity == Severity.ERROR }
        val warnings = findings.count { it.severity == Severity.WARNING }
        val infos = findings.count { it.severity == Severity.INFO }
        sb.appendLine("${c(BOLD)}${findings.size} finding(s):${c(RESET)} $errors error, $warnings warning, $infos info")
        return sb.toString()
    }

    private fun severityRank(s: Severity): Int = when (s) {
        Severity.ERROR -> 0
        Severity.WARNING -> 1
        Severity.INFO -> 2
    }
}
