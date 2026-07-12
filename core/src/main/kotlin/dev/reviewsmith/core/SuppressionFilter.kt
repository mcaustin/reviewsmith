package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding

data class SuppressionResult(
    val surfaced: List<Finding>,
    val suppressed: List<Finding>,
    val notices: List<String>,
)

/**
 * Suppresses findings against the inline `reviewsmith-disable*` directives scanned from the
 * reviewed files. Runs on post-validator findings, before the baseline filter.
 *
 * Matching honors that LLM-assigned line numbers drift:
 *  - `disable-next-line` / `disable-line` match a finding of the same rule whose line falls
 *    within [band] lines of the directive's target line (a small tolerance band).
 *  - `disable` … `enable` block and `disable-file` are exact line-range containment — no band,
 *    so drift inside the range is irrelevant.
 * A finding with no line can only be caught by a file- or block-scope directive.
 *
 * Directives that match no finding are reported as notices, as are directives passed through
 * from the scanner (bare-directive refusals, missing-reason nags).
 */
object SuppressionFilter {
    fun apply(
        findings: List<Finding>,
        suppressionsByFile: Map<String, FileSuppressions>,
        band: Int,
    ): SuppressionResult {
        val scannerNotices = suppressionsByFile.values.flatMap { it.notices }
        if (suppressionsByFile.values.all { it.directives.isEmpty() }) {
            return SuppressionResult(findings, emptyList(), scannerNotices)
        }

        val used = HashSet<SuppressionDirective>()
        val surfaced = mutableListOf<Finding>()
        val suppressed = mutableListOf<Finding>()

        for (finding in findings) {
            val fileKey = Fingerprint.normalizeFile(finding.file)
            val directives = suppressionsByFile[fileKey]?.directives ?: emptyList()
            val hit = directives.firstOrNull { matches(it, finding, directives, band) }
            if (hit != null) {
                used.add(hit)
                suppressed.add(finding)
            } else {
                surfaced.add(finding)
            }
        }

        val unusedNotices = suppressionsByFile.entries.flatMap { (file, fs) ->
            fs.directives
                .filter { it.kind != SuppressionKind.BLOCK_ENABLE && it !in used }
                .map { d ->
                    "Reviewsmith: unused suppression at $file:${d.line} — " +
                        "${d.ruleIds.joinToString(",")} matched no finding."
                }
        }

        return SuppressionResult(surfaced, suppressed, scannerNotices + unusedNotices)
    }

    private fun matches(
        directive: SuppressionDirective,
        finding: Finding,
        allInFile: List<SuppressionDirective>,
        band: Int,
    ): Boolean {
        if (directive.kind == SuppressionKind.BLOCK_ENABLE) return false
        if (!directive.coversRule(finding.ruleId)) return false
        return when (directive.kind) {
            SuppressionKind.FILE -> true
            SuppressionKind.BLOCK_DISABLE -> inOpenBlock(directive, finding, allInFile)
            SuppressionKind.NEXT_LINE -> {
                val line = finding.line ?: return false
                val target = directive.line + 1
                line in (target - band)..(target + band)
            }
            SuppressionKind.SAME_LINE -> {
                val line = finding.line ?: return false
                line in (directive.line - band)..(directive.line + band)
            }
            SuppressionKind.BLOCK_ENABLE -> false
        }
    }

    /**
     * True when [finding] falls inside the range opened by this block-disable and closed by
     * the next matching `enable` (or EOF). A finding with no line cannot be range-contained.
     */
    private fun inOpenBlock(
        open: SuppressionDirective,
        finding: Finding,
        allInFile: List<SuppressionDirective>,
    ): Boolean {
        val line = finding.line ?: return false
        if (line < open.line) return false
        val close = allInFile
            .filter { it.kind == SuppressionKind.BLOCK_ENABLE && it.line > open.line }
            .filter { it.ruleIds.isEmpty() || it.ruleIds.any { id -> open.coversRule(id) } }
            .minByOrNull { it.line }
        return close == null || line <= close.line
    }
}
