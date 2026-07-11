package dev.reviewsmith.core

object Prompts {
    val findingsSchema: String = """
        {
          "type": "object",
          "properties": {
            "findings": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "file": { "type": "string" },
                  "line": { "type": "integer" },
                  "severity": { "type": "string", "enum": ["INFO", "WARNING", "ERROR"] },
                  "message": { "type": "string" },
                  "rationale": { "type": "string" },
                  "suggestedFix": { "type": "string" }
                },
                "required": ["file", "severity", "message"]
              }
            }
          },
          "required": ["findings"]
        }
    """.trimIndent()

    fun ruleSystemPrompt(): String = """
        You are Reviewsmith, an automated code reviewer. You are given one review rule and
        a set of changed files in a repository you can read with your tools. Apply ONLY the
        given rule. Read the referenced project docs and any surrounding code you need.
        Report only genuine violations caused by or related to the changed code — never
        pre-existing, unrelated issues. If you are unsure whether something is a real
        defect, do not report it. When — and only when — a single concrete change fixes
        the finding, set "suggestedFix" to a short description of that change (name the
        replacement, e.g. "use coerceAtMost(cap)"); omit it for anything needing judgment
        or multiple edits. Return your findings using the required JSON output schema and
        nothing else.
    """.trimIndent()

    fun validatorSystemPrompt(): String = """
        You are a skeptical senior reviewer auditing findings produced by automated review
        rules. When a unified diff of the change is provided, use it to verify each finding is
        actually caused by the changed lines — discard findings whose cited defect is not
        introduced or touched by the diff (pre-existing code the change merely sits near).
        Also discard findings that are factually wrong, noise (e.g. wrapping a non-nullable in
        a null guard), or convention claims contradicted by the project's own docs. Keep only
        genuinely correct, actionable findings. For
        each kept finding, set "confidence" to "CLEAR" for a mechanical single-fix issue
        with low blast radius, or "AMBIGUOUS" for a judgment call or anything changing
        behavior or a public API. Preserve a finding's "suggestedFix" when it is present
        and correct; drop or correct it if wrong, and add one only when a single concrete
        change fixes the finding. Return the kept findings using the required JSON schema,
        adding a "confidence" field to each.
    """.trimIndent()

    val validatorSchema: String = """
        {
          "type": "object",
          "properties": {
            "findings": {
              "type": "array",
              "items": {
                "type": "object",
                "properties": {
                  "ruleId": { "type": "string" },
                  "file": { "type": "string" },
                  "line": { "type": "integer" },
                  "severity": { "type": "string", "enum": ["INFO", "WARNING", "ERROR"] },
                  "message": { "type": "string" },
                  "rationale": { "type": "string" },
                  "suggestedFix": { "type": "string" },
                  "confidence": { "type": "string", "enum": ["CLEAR", "AMBIGUOUS"] }
                },
                "required": ["ruleId", "file", "severity", "message", "confidence"]
              }
            }
          },
          "required": ["findings"]
        }
    """.trimIndent()

    fun ruleUserPrompt(rule: Rule, targets: List<String>, docs: List<String>, diff: String = ""): String = buildString {
        appendLine("# Rule: ${rule.name} (id: ${rule.id})")
        appendLine()
        appendLine(rule.body)
        appendLine()
        appendDiffBlock(diff, "What changed (unified diff, ±5 lines)")
        appendLine("## Changed files to review")
        targets.forEach { appendLine("- $it") }
        appendLine()
        appendLine("Focus on the changed lines above; Read the files for the surrounding context you need.")
        if (docs.isNotEmpty()) {
            appendLine()
            appendLine("## Project docs to read for context (read the relevant ones)")
            docs.forEach { appendLine("- $it") }
        }
    }

    fun validatorUserPrompt(rawFindingsJson: String, docs: List<String>, diff: String = ""): String = buildString {
        appendLine("# Findings to audit")
        appendLine()
        appendLine("Below are candidate findings as JSON. Audit each per your instructions.")
        appendLine()
        appendLine("```json")
        appendLine(rawFindingsJson)
        appendLine("```")
        appendLine()
        appendDiffBlock(
            diff,
            "What changed (unified diff, ±5 lines) — use it to confirm each finding is caused by the change",
        )
        if (docs.isNotEmpty()) {
            appendLine()
            appendLine("## Project docs available for verification")
            docs.forEach { appendLine("- $it") }
        }
    }

    private fun StringBuilder.appendDiffBlock(diff: String, heading: String) {
        if (diff.isBlank()) return
        appendLine("## $heading")
        appendLine()
        appendLine("```diff")
        appendLine(diff.trim())
        appendLine("```")
        appendLine()
    }
}
