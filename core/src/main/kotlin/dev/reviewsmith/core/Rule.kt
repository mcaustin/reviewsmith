package dev.reviewsmith.core

import dev.reviewsmith.spi.Severity

data class Rule(
    val id: String,
    val name: String,
    val severity: Severity,
    val appliesTo: List<String>,
    val docs: List<String>,
    val tags: List<String>,
    val body: String,
    val maxBudgetUsd: Double? = null,
)

/**
 * Parses a markdown rule file with optional YAML frontmatter. Accepts the
 * `.claude/rules` key aliases (`paths` -> `appliesTo`, `description` -> `name`) so those
 * files load unmodified.
 */
object RuleParser {
    fun parse(id: String, text: String): Rule {
        val frontmatter: Map<String, String>
        val body: String
        if (text.startsWith("---")) {
            val end = text.indexOf("\n---", 3)
            if (end >= 0) {
                val fmBlock = text.substring(3, end).trim()
                frontmatter = parseFrontmatter(fmBlock)
                body = text.substring(text.indexOf('\n', end + 1) + 1).trim()
            } else {
                frontmatter = emptyMap()
                body = text
            }
        } else {
            frontmatter = emptyMap()
            body = text
        }

        val name = frontmatter["name"]
            ?: frontmatter["description"]
            ?: firstHeading(body)
            ?: id
        val severity = frontmatter["severity"]?.let {
            runCatching { Severity.valueOf(it.uppercase()) }.getOrNull()
        } ?: Severity.WARNING
        val appliesTo = splitList(frontmatter["appliesTo"] ?: frontmatter["paths"])
        val docs = splitList(frontmatter["docs"])
        val tags = splitList(frontmatter["tags"])
        val maxBudgetUsd = frontmatter["maxBudgetUsd"]?.let { raw ->
            raw.toDoubleOrNull().also {
                if (it == null) System.err.println(
                    "Reviewsmith: rule '$id': invalid maxBudgetUsd value \"$raw\" — no budget cap applied.",
                )
            }
        }

        return Rule(id, name, severity, appliesTo, docs, tags, body, maxBudgetUsd)
    }

    private fun firstHeading(body: String): String? =
        body.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()

    private fun parseFrontmatter(block: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        var currentKey: String? = null
        val listAccum = StringBuilder()
        for (raw in block.lines()) {
            val line = raw.trimEnd()
            if (line.isBlank()) continue
            val listItem = line.trim()
            if (listItem.startsWith("- ") && currentKey != null) {
                if (listAccum.isNotEmpty()) listAccum.append(',')
                listAccum.append(listItem.removePrefix("- ").trim().trim('"', '\''))
                out[currentKey!!] = listAccum.toString()
                continue
            }
            val colon = line.indexOf(':')
            if (colon > 0 && !line.startsWith(" ")) {
                currentKey = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim()
                listAccum.clear()
                if (value.isNotEmpty()) {
                    out[currentKey!!] = stripInlineList(value)
                }
            }
        }
        return out
    }

    private fun stripInlineList(value: String): String {
        val v = value.trim()
        if (v.startsWith("[") && v.endsWith("]")) {
            return v.substring(1, v.length - 1)
                .split(',')
                .joinToString(",") { it.trim().trim('"', '\'') }
        }
        return v.trim('"', '\'')
    }

    private fun splitList(value: String?): List<String> =
        value?.split(',')?.map { it.trim().trim('"', '\'') }?.filter { it.isNotEmpty() }
            ?: emptyList()
}
