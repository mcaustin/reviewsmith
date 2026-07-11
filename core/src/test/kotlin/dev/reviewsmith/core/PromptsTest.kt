package dev.reviewsmith.core

import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PromptsTest {

    private val rule = Rule("correctness", "Correctness", Severity.WARNING, listOf("**/*.kt"), emptyList(), emptyList(), "check bugs")
    private val diff = "diff --git a/A.kt b/A.kt\n@@ -1 +1 @@\n-old\n+new"

    @Test
    fun `rule prompt embeds the diff hunk when present`() {
        val prompt = Prompts.ruleUserPrompt(rule, listOf("A.kt"), emptyList(), diff)
        assertTrue(prompt.contains("What changed (unified diff"), "diff heading present")
        assertTrue(prompt.contains("```diff"), "diff fence present")
        assertTrue(prompt.contains("+new"), "diff body present")
    }

    @Test
    fun `rule prompt omits the diff block when diff is blank`() {
        val prompt = Prompts.ruleUserPrompt(rule, listOf("A.kt"), emptyList(), "")
        assertFalse(prompt.contains("```diff"), "no diff fence when no diff")
        assertFalse(prompt.contains("What changed"), "no diff heading when no diff")
        assertTrue(prompt.contains("A.kt"), "file list still present")
    }

    @Test
    fun `validator prompt embeds the diff and asks to confirm causation`() {
        val prompt = Prompts.validatorUserPrompt("""{"findings":[]}""", emptyList(), diff)
        assertTrue(prompt.contains("```diff"), "diff fence present")
        assertTrue(prompt.contains("caused by the change"), "causation instruction present")
        assertTrue(prompt.contains("+new"))
    }

    @Test
    fun `validator prompt omits the diff block when diff is blank`() {
        val prompt = Prompts.validatorUserPrompt("""{"findings":[]}""", emptyList(), "")
        assertFalse(prompt.contains("```diff"))
    }
}
