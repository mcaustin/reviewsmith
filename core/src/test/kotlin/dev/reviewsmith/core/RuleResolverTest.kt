package dev.reviewsmith.core

import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class RuleResolverTest {

    private fun writeRule(dir: Path, name: String, body: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(name), body)
    }

    @Test
    fun `discovers claude-rules by filename as id`(@TempDir repo: Path) {
        writeRule(
            repo.resolve(".claude/rules"), "kotlin.md",
            "---\npaths:\n  - \"**/*.kt\"\n---\n# Kotlin\nrule body",
        )
        val config = ReviewsmithConfig(ruleSources = listOf(".claude/rules"))

        val rules = RuleResolver.resolve(repo, config)

        assertEquals(1, rules.size)
        assertEquals("kotlin", rules[0].id)
        assertEquals(listOf("**/*.kt"), rules[0].appliesTo)
    }

    @Test
    fun `later source overrides earlier by id`(@TempDir repo: Path) {
        writeRule(repo.resolve(".claude/rules"), "kotlin.md", "---\nseverity: warning\n---\nfrom claude")
        writeRule(repo.resolve("reviewsmith/rules"), "kotlin.md", "---\nseverity: error\n---\nfrom reviewsmith")
        val config = ReviewsmithConfig(ruleSources = listOf(".claude/rules", "reviewsmith/rules"))

        val rules = RuleResolver.resolve(repo, config)

        assertEquals(1, rules.size)
        assertEquals(Severity.ERROR, rules[0].severity)
        assertTrue(rules[0].body.contains("from reviewsmith"))
    }

    @Test
    fun `missing source directory is skipped`(@TempDir repo: Path) {
        val config = ReviewsmithConfig(ruleSources = listOf(".claude/rules"))
        assertTrue(RuleResolver.resolve(repo, config).isEmpty())
    }

    @Test
    fun `shipped source loads all bundled rules`(@TempDir repo: Path) {
        val config = ReviewsmithConfig(ruleSources = listOf("shipped"))
        val rules = RuleResolver.resolve(repo, config)
        val ids = rules.map { it.id }.toSet()
        assertTrue(
            ids.containsAll(
                setOf(
                    "correctness-safety", "simplification", "style-convention",
                    "design-impact", "evolution-safety",
                    "secrets-in-code", "pii-logging", "backward-compatible-migrations",
                ),
            ),
        )
    }

    @Test
    fun `per-rule disable drops the rule`(@TempDir repo: Path) {
        writeRule(repo.resolve(".claude/rules"), "kotlin.md", "---\n---\nbody")
        val config = ReviewsmithConfig(
            ruleSources = listOf(".claude/rules"),
            rules = mapOf("kotlin" to RuleOverride(enabled = false)),
        )
        assertTrue(RuleResolver.resolve(repo, config).isEmpty())
    }

    @Test
    fun `per-rule severity override applies`(@TempDir repo: Path) {
        writeRule(repo.resolve(".claude/rules"), "kotlin.md", "---\nseverity: warning\n---\nbody")
        val config = ReviewsmithConfig(
            ruleSources = listOf(".claude/rules"),
            rules = mapOf("kotlin" to RuleOverride(severity = "error")),
        )
        val rules = RuleResolver.resolve(repo, config)
        assertEquals(Severity.ERROR, rules[0].severity)
    }

    @Test
    fun `invalid severity override falls back to the rule default`(@TempDir repo: Path) {
        writeRule(repo.resolve(".claude/rules"), "kotlin.md", "---\nseverity: warning\n---\nbody")
        val config = ReviewsmithConfig(
            ruleSources = listOf(".claude/rules"),
            rules = mapOf("kotlin" to RuleOverride(severity = "eror")),
        )
        val rules = RuleResolver.resolve(repo, config)
        assertEquals(Severity.WARNING, rules[0].severity)
    }

    @Test
    fun `config maxBudgetUsd override wins over rule frontmatter`(@TempDir repo: Path) {
        writeRule(repo.resolve(".claude/rules"), "kotlin.md", "---\nmaxBudgetUsd: 0.10\n---\nbody")
        val config = ReviewsmithConfig(
            ruleSources = listOf(".claude/rules"),
            rules = mapOf("kotlin" to RuleOverride(maxBudgetUsd = 0.02)),
        )
        val rules = RuleResolver.resolve(repo, config)
        assertEquals(0.02, rules[0].maxBudgetUsd!!, 1e-9)
    }

    @Test
    fun `rule frontmatter maxBudgetUsd survives when no override`(@TempDir repo: Path) {
        writeRule(repo.resolve(".claude/rules"), "kotlin.md", "---\nmaxBudgetUsd: 0.10\n---\nbody")
        val config = ReviewsmithConfig(ruleSources = listOf(".claude/rules"))
        assertEquals(0.10, RuleResolver.resolve(repo, config)[0].maxBudgetUsd!!, 1e-9)
    }

    @Test
    fun `config callTimeoutSeconds override wins over rule frontmatter`(@TempDir repo: Path) {
        writeRule(repo.resolve(".claude/rules"), "kotlin.md", "---\ncallTimeoutSeconds: 180\n---\nbody")
        val config = ReviewsmithConfig(
            ruleSources = listOf(".claude/rules"),
            rules = mapOf("kotlin" to RuleOverride(callTimeoutSeconds = 90)),
        )
        assertEquals(90L, RuleResolver.resolve(repo, config)[0].callTimeoutSeconds)
    }

    @Test
    fun `rule frontmatter callTimeoutSeconds survives when no override`(@TempDir repo: Path) {
        writeRule(repo.resolve(".claude/rules"), "kotlin.md", "---\ncallTimeoutSeconds: 120\n---\nbody")
        val config = ReviewsmithConfig(ruleSources = listOf(".claude/rules"))
        assertEquals(120L, RuleResolver.resolve(repo, config)[0].callTimeoutSeconds)
    }

    @Test
    fun `default source order includes shipped then repo dirs`(@TempDir repo: Path) {
        writeRule(repo.resolve(".claude/rules"), "custom.md", "---\n---\nbody")
        val rules = RuleResolver.resolve(repo, ReviewsmithConfig())
        val ids = rules.map { it.id }.toSet()
        assertTrue(ids.contains("correctness-safety"), "shipped rules present by default")
        assertTrue(ids.contains("custom"), "claude/rules discovered by default")
    }

    @Test
    fun `buildUponDefault false drops shipped rules`(@TempDir repo: Path) {
        writeRule(repo.resolve(".claude/rules"), "custom.md", "---\n---\nbody")
        val rules = RuleResolver.resolve(repo, ReviewsmithConfig(buildUponDefault = false))
        val ids = rules.map { it.id }.toSet()
        assertFalse(ids.contains("correctness-safety"), "shipped rules must be dropped: $ids")
        assertTrue(ids.contains("custom"), "user rules still resolve")
    }

    @Test
    fun `explicit ruleSources overrides buildUponDefault`(@TempDir repo: Path) {
        val config = ReviewsmithConfig(buildUponDefault = false, ruleSources = listOf("shipped"))
        val ids = RuleResolver.resolve(repo, config).map { it.id }.toSet()
        assertTrue(ids.contains("correctness-safety"), "explicit ruleSources wins over buildUponDefault")
    }
}
