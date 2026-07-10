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
        assertNull(c.scope.baseRef)
        assertTrue(c.scope.detectBase)
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

    @Test
    fun `default gate is fully advisory`() {
        val gate = ReviewsmithConfig.parse("{}").gate
        assertEquals(FailOnLevel.NONE, gate.failOnLevel())
        assertTrue(gate.failOnCategory.isEmpty())
    }

    @Test
    fun `gate failOn parses case-insensitively`() {
        assertEquals(FailOnLevel.WARNING, ReviewsmithConfig.parse("gate:\n  failOn: warning").gate.failOnLevel())
        assertEquals(FailOnLevel.ERROR, ReviewsmithConfig.parse("gate:\n  failOn: error").gate.failOnLevel())
    }

    @Test
    fun `gate failOnCategory parses as a list`() {
        val gate = ReviewsmithConfig.parse(
            """
            gate:
              failOn: error
              failOnCategory: [safety, auth]
            """.trimIndent(),
        ).gate
        assertEquals(listOf("safety", "auth"), gate.failOnCategory)
    }

    @Test
    fun `design-doc gate example parses without throwing`() {
        val gate = ReviewsmithConfig.parse("gate:\n  failOn: warning\n  onlyConfidence: clear").gate
        assertEquals(FailOnLevel.WARNING, gate.failOnLevel())
        assertEquals("clear", gate.onlyConfidence)
    }

    @Test
    fun `agent isolation defaults to strict hermetic`() {
        val agent = ReviewsmithConfig.parse("{}").agent
        assertEquals("strict", agent.isolation)
        assertTrue(agent.hermetic())
    }

    @Test
    fun `agent isolation local is not hermetic`() {
        assertFalse(ReviewsmithConfig.parse("agent:\n  isolation: local").agent.hermetic())
        assertFalse(ReviewsmithConfig.parse("agent:\n  isolation: LOCAL").agent.hermetic())
    }

    @Test
    fun `per-rule maxBudgetUsd override parses`() {
        val c = ReviewsmithConfig.parse(
            """
            rules:
              design-impact:
                maxBudgetUsd: 0.05
            """.trimIndent(),
        )
        assertEquals(0.05, c.rules.getValue("design-impact").maxBudgetUsd!!, 1e-9)
    }
}
