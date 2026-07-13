package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity

class ConsoleReporter(private val useColor: Boolean = true) : Reporter {
    private val ESC = ""
    private val RESET = "$ESC[0m"
    private val RED = "$ESC[31m"
    private val YELLOW = "$ESC[33m"
    private val BLUE = "$ESC[34m"
    private val BOLD = "$ESC[1m"
    private val DIM = "$ESC[2m"

    override fun report(result: RunResult): String {
        val findings = result.findings
        val suppressedByBaseline = result.suppressedByBaseline
        val suppressedInline = result.suppressedInline
        val abandonedUnits = result.abandonedUnits
        val cacheHits = result.cacheHits
        fun c(code: String) = if (useColor) code else ""
        val abandonSuffix =
            if (abandonedUnits > 0) "  |  $abandonedUnits unit(s) abandoned (timeout or error)" else ""
        val cacheSuffix = if (cacheHits > 0) "  |  $cacheHits cache hit(s)" else ""
        val inlineSuffix = if (suppressedInline > 0) "  |  $suppressedInline suppressed inline" else ""
        val hiddenSuffix = if (result.hiddenByLevel > 0) "  |  ${result.hiddenByLevel} hidden below report level" else ""
        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("${c(BOLD)}Reviewsmith${c(RESET)} — reviewed ${result.filesReviewed} file(s)")
        sb.appendLine()

        if (findings.isEmpty()) {
            if (suppressedByBaseline > 0) {
                sb.appendLine("${c(BLUE)}No new findings.${c(RESET)} ($suppressedByBaseline suppressed by baseline)$inlineSuffix$hiddenSuffix$abandonSuffix$cacheSuffix")
            } else {
                sb.appendLine("${c(BLUE)}No findings.${c(RESET)}$inlineSuffix$hiddenSuffix$abandonSuffix$cacheSuffix")
            }
            return sb.toString()
        }

        val groups = findings.groupBy { it.ruleId }.entries
            .sortedWith(compareBy({ minSeverityRank(it.value) }, { it.key }))
        for ((ruleId, groupFindings) in groups) {
            val label = ruleId.ifBlank { "(unattributed)" }
            sb.appendLine("${c(BOLD)}▸ $label${c(RESET)} ${c(DIM)}(${groupFindings.size})${c(RESET)}")
            for (f in groupFindings.sortedWith(compareBy({ severityRank(it.severity) }, { it.file }))) {
                val color = when (f.severity) {
                    Severity.ERROR -> c(RED)
                    Severity.WARNING -> c(YELLOW)
                    Severity.INFO -> c(BLUE)
                }
                val loc = f.line?.let { ":$it" } ?: ""
                val conf = f.confidence?.let { " ${c(DIM)}[${it.name.lowercase()}]${c(RESET)}" } ?: ""
                sb.appendLine("  $color${f.severity}${c(RESET)} ${c(BOLD)}${f.file}$loc${c(RESET)}$conf")
                sb.appendLine("    ${f.message}")
                f.rationale?.takeIf { it.isNotBlank() }?.let {
                    sb.appendLine("    ${c(DIM)}$it${c(RESET)}")
                }
                f.suggestedFix?.takeIf { it.isNotBlank() }?.let {
                    sb.appendLine("    ${c(DIM)}fix: $it${c(RESET)}")
                }
                sb.appendLine()
            }
        }

        val errors = findings.count { it.severity == Severity.ERROR }
        val warnings = findings.count { it.severity == Severity.WARNING }
        val infos = findings.count { it.severity == Severity.INFO }
        val baselineSuffix = if (suppressedByBaseline > 0) "  |  $suppressedByBaseline suppressed by baseline" else ""
        sb.appendLine("${c(BOLD)}${findings.size} finding(s):${c(RESET)} $errors error, $warnings warning, $infos info$baselineSuffix$inlineSuffix$hiddenSuffix$abandonSuffix$cacheSuffix")
        return sb.toString()
    }

    private fun severityRank(s: Severity): Int = when (s) {
        Severity.ERROR -> 0
        Severity.WARNING -> 1
        Severity.INFO -> 2
    }

    private fun minSeverityRank(findings: List<Finding>): Int =
        findings.minOf { severityRank(it.severity) }
}
