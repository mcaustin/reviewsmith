package dev.reviewsmith.core

import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RuleParserTest {

    @Test
    fun `maxBudgetUsd parses from frontmatter`() {
        val rule = RuleParser.parse("x", "---\nmaxBudgetUsd: 0.05\n---\nbody")
        assertEquals(0.05, rule.maxBudgetUsd!!, 1e-9)
    }

    @Test
    fun `absent maxBudgetUsd is null`() {
        assertNull(RuleParser.parse("x", "---\nseverity: warning\n---\nbody").maxBudgetUsd)
    }

    @Test
    fun `malformed maxBudgetUsd is null`() {
        assertNull(RuleParser.parse("x", "---\nmaxBudgetUsd: cheap\n---\nbody").maxBudgetUsd)
    }

    @Test
    fun `callTimeoutSeconds parses from frontmatter`() {
        assertEquals(180L, RuleParser.parse("x", "---\ncallTimeoutSeconds: 180\n---\nbody").callTimeoutSeconds)
    }

    @Test
    fun `absent callTimeoutSeconds is null`() {
        assertNull(RuleParser.parse("x", "---\nseverity: warning\n---\nbody").callTimeoutSeconds)
    }

    @Test
    fun `malformed callTimeoutSeconds is null`() {
        assertNull(RuleParser.parse("x", "---\ncallTimeoutSeconds: soon\n---\nbody").callTimeoutSeconds)
    }

    @Test
    fun `maps claude-rules frontmatter aliases`() {
        // Mirrors toast-publish/.claude/rules/kotlin.md: block-list `paths`, no name/severity.
        val text =
            """
            ---
            paths:
              - "**/*.kt"
              - "publish/api/**"
            ---

            # Kotlin Write-Time Guardrails

            - New mutable state in a @Singleton must justify its lifecycle.
            """.trimIndent()

        val rule = RuleParser.parse("kotlin", text)

        assertEquals("kotlin", rule.id)
        // first heading becomes the name when frontmatter has none
        assertEquals("Kotlin Write-Time Guardrails", rule.name)
        // `paths` alias -> appliesTo, multi-line block list parsed
        assertEquals(listOf("**/*.kt", "publish/api/**"), rule.appliesTo)
        // default severity when unspecified
        assertEquals(Severity.WARNING, rule.severity)
        assertTrue(rule.body.contains("@Singleton"))
    }

    @Test
    fun `explicit name and severity win over aliases`() {
        val text =
            """
            ---
            name: My Rule
            description: ignored when name present
            severity: error
            appliesTo: ["**/*.ts"]
            ---
            body here
            """.trimIndent()

        val rule = RuleParser.parse("x", text)
        assertEquals("My Rule", rule.name)
        assertEquals(Severity.ERROR, rule.severity)
        assertEquals(listOf("**/*.ts"), rule.appliesTo)
    }

    @Test
    fun `no frontmatter falls back to id and heading`() {
        val rule = RuleParser.parse("plain", "# A Heading\n\nsome text")
        assertEquals("A Heading", rule.name)
        assertTrue(rule.appliesTo.isEmpty())
    }
}
