package dev.reviewsmith.core

enum class SuppressionKind {
    NEXT_LINE,
    SAME_LINE,
    BLOCK_DISABLE,
    BLOCK_ENABLE,
    FILE,
}

/**
 * One `reviewsmith-disable*` / `reviewsmith-enable` directive parsed from a source file.
 * [ruleIds] empty means the directive named no rule; such a directive is refused by the
 * scanner (a blanket mute is a footgun), so a retained directive always carries at least
 * one id. [line] is 1-based and is the line the comment sits on. [reason] is the text after
 * `--`, if any.
 */
data class SuppressionDirective(
    val kind: SuppressionKind,
    val ruleIds: Set<String>,
    val line: Int,
    val reason: String?,
    val raw: String,
) {
    fun coversRule(ruleId: String): Boolean = ruleIds.contains(ruleId)
}

/** Directives kept for a file plus the notices the scanner produced while parsing it. */
data class FileSuppressions(
    val directives: List<SuppressionDirective>,
    val notices: List<String>,
)

/**
 * Scans source text for `reviewsmith-disable*` / `reviewsmith-enable` directives inside line
 * or block comments (`//`, `#`, `--`, `/* */`), across the languages Reviewsmith reviews.
 *
 * Contract (see design): a directive MUST name at least one ruleId — a bare directive is
 * refused (not honored) with a notice, because a blanket mute silently deletes findings from
 * unrelated rules. A missing reason is honored but nagged.
 */
object SuppressionScanner {
    private val DIRECTIVE = Regex(
        """reviewsmith-(disable-next-line|disable-line|disable-file|disable|enable)\b([^\n]*)""",
    )

    fun scan(file: String, text: String): FileSuppressions {
        val directives = mutableListOf<SuppressionDirective>()
        val notices = mutableListOf<String>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val match = DIRECTIVE.find(rawLine) ?: return@forEachIndexed
            val lineNo = index + 1
            val kind = when (match.groupValues[1]) {
                "disable-next-line" -> SuppressionKind.NEXT_LINE
                "disable-line" -> SuppressionKind.SAME_LINE
                "disable-file" -> SuppressionKind.FILE
                "disable" -> SuppressionKind.BLOCK_DISABLE
                "enable" -> SuppressionKind.BLOCK_ENABLE
                else -> return@forEachIndexed
            }
            val tail = match.groupValues[2]
            val (ruleIds, reason) = parseTail(tail)

            if (kind != SuppressionKind.BLOCK_ENABLE && ruleIds.isEmpty()) {
                notices.add(
                    "Reviewsmith: ignoring suppression at $file:$lineNo — name a rule, " +
                        "e.g. 'reviewsmith-${match.groupValues[1]} correctness-safety'.",
                )
                return@forEachIndexed
            }
            if (kind != SuppressionKind.BLOCK_ENABLE && reason == null) {
                notices.add(
                    "Reviewsmith: suppression at $file:$lineNo has no reason — add '-- why' " +
                        "(e.g. '-- verified safe: inputs are distinct').",
                )
            }
            directives.add(
                SuppressionDirective(kind, ruleIds, lineNo, reason, rawLine.trim()),
            )
        }
        return FileSuppressions(directives, notices)
    }

    /** Splits the directive tail into (ruleIds, reason). Reason is everything after `--`. */
    private fun parseTail(tail: String): Pair<Set<String>, String?> {
        val reasonIdx = tail.indexOf("--")
        val idsPart = if (reasonIdx >= 0) tail.substring(0, reasonIdx) else tail
        val reason = if (reasonIdx >= 0) {
            tail.substring(reasonIdx + 2).trim().trimEnd('*', '/').trim().ifEmpty { null }
        } else {
            null
        }
        val ids = idsPart
            .split(',', ' ')
            .map { it.trim().trimEnd('*', '/').trim() }
            .filter { it.isNotEmpty() }
            .toSet()
        return ids to reason
    }
}
