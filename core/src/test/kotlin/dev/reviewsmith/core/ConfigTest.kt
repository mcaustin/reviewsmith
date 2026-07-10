package dev.reviewsmith.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigTest {

    @Test
    fun `empty config yields defaults`() {
        val c = ReviewsmithConfig.parse("{}")
        assertEquals("changed", c.scope.default)
        assertEquals(4, c.maxConcurrency)
        assertTrue(c.validator.enabled)
        assertTrue(c.buildUponDefault)
    }

    @Test
    fun `callTimeoutSeconds defaults to 300 and is configurable`() {
        assertEquals(300L, ReviewsmithConfig.parse("{}").callTimeoutSeconds)
        assertEquals(60L, ReviewsmithConfig.parse("callTimeoutSeconds: 60").callTimeoutSeconds)
    }

    @Test
    fun `blank or whitespace config yields defaults instead of throwing`() {
        assertEquals(4, ReviewsmithConfig.parse("").maxConcurrency)
        assertEquals(4, ReviewsmithConfig.parse("   \n  ").maxConcurrency)
    }

    @Test
    fun `partial config overrides only named fields`() {
        val c = ReviewsmithConfig.parse(
            """
            maxConcurrency: 8
            validator:
              enabled: false
            """.trimIndent(),
        )
        assertEquals(8, c.maxConcurrency)
        assertFalse(c.validator.enabled)
        // untouched fields keep defaults
        assertEquals("changed", c.scope.default)
        assertEquals("origin/main", c.scope.baseRef)
    }

    @Test
    fun `default rule sources when unset`() {
        val c = ReviewsmithConfig.parse("{}")
        assertNull(c.ruleSources)
        assertEquals(
            listOf("shipped", ".claude/rules", "reviewsmith/rules"),
            c.effectiveRuleSources(),
        )
    }

    @Test
    fun `explicit rule sources replace the default order`() {
        val c = ReviewsmithConfig.parse(
            """
            ruleSources:
              - reviewsmith/rules
            """.trimIndent(),
        )
        assertEquals(listOf("reviewsmith/rules"), c.effectiveRuleSources())
    }

    @Test
    fun `per-rule overrides parse`() {
        val c = ReviewsmithConfig.parse(
            """
            rules:
              kotlin:
                enabled: false
              testing:
                severity: error
            """.trimIndent(),
        )
        assertFalse(c.rules.getValue("kotlin").enabled!!)
        assertEquals("error", c.rules.getValue("testing").severity)
    }
}
