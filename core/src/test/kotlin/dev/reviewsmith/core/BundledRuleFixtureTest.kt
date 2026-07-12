package dev.reviewsmith.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * Deterministic harness: for each newly-shipped rule, assert the rule loads from the
 * `shipped` source and its `appliesTo` globs cover the rule's fixture sample paths. Does
 * NOT invoke the agent — structural properties only (per design §8, assertions stay
 * tolerant: rule presence + glob coverage, never LLM output).
 */
class BundledRuleFixtureTest {

    private val fixtureBase = "reviewsmith/fixtures"

    private fun shippedRules(repo: Path): Map<String, Rule> =
        RuleResolver.resolve(repo, ReviewsmithConfig(ruleSources = listOf("shipped")))
            .associateBy { it.id }

    /** A representative fixture path (repo-relative) for each rule, matching its globs. */
    private val fixtureSamples = mapOf(
        "simplification" to listOf(
            "$fixtureBase/simplification/positive/sample.kt",
            "$fixtureBase/simplification/negative/sample.kt",
        ),
        "style-convention" to listOf(
            "$fixtureBase/style-convention/positive/sample.kt",
            "$fixtureBase/style-convention/negative/sample.kt",
        ),
        "design-impact" to listOf(
            "$fixtureBase/design-impact/positive/Subject.kt",
            "$fixtureBase/design-impact/positive/Caller.kt",
            "$fixtureBase/design-impact/negative/Subject.kt",
        ),
        "secrets-in-code" to listOf(
            "$fixtureBase/secrets-in-code/positive/sample.kt",
            "$fixtureBase/secrets-in-code/negative/sample.kt",
        ),
        "pii-logging" to listOf(
            "$fixtureBase/pii-logging/positive/sample.kt",
            "$fixtureBase/pii-logging/negative/sample.kt",
        ),
        "backward-compatible-migrations" to listOf(
            "$fixtureBase/backward-compatible-migrations/positive/migration/V1__drop_column.sql",
            "$fixtureBase/backward-compatible-migrations/negative/migration/V2__add_nullable.sql",
        ),
        "typescript-safety" to listOf(
            "$fixtureBase/typescript-safety/positive/sample.ts",
            "$fixtureBase/typescript-safety/negative/sample.ts",
        ),
    )

    @Test
    fun `all bundled rules are shipped`(@TempDir repo: Path) {
        val ids = shippedRules(repo).keys
        assertTrue(
            ids.containsAll(
                setOf(
                    "correctness-safety", "simplification", "style-convention",
                    "design-impact", "evolution-safety",
                    "secrets-in-code", "pii-logging", "backward-compatible-migrations",
                    "typescript-safety",
                ),
            ),
            "expected all 9 bundled rule ids, got $ids",
        )
    }

    @Test
    fun `heavy rules ship with cost and time guardrails`(@TempDir repo: Path) {
        val rules = shippedRules(repo)
        val correctness = rules.getValue("correctness-safety")
        val design = rules.getValue("design-impact")
        assertEquals(180L, correctness.callTimeoutSeconds)
        assertEquals(1.25, correctness.maxBudgetUsd!!, 1e-9)
        assertEquals(180L, design.callTimeoutSeconds)
        assertEquals(1.00, design.maxBudgetUsd!!, 1e-9)
    }

    @Test
    fun `cheap rules ship without guardrails`(@TempDir repo: Path) {
        val secrets = shippedRules(repo).getValue("secrets-in-code")
        assertNull(secrets.callTimeoutSeconds)
        assertNull(secrets.maxBudgetUsd)
    }

    @Test
    fun `each new rule loads and its globs cover its fixture paths`(@TempDir repo: Path) {
        val rules = shippedRules(repo)
        for ((id, samplePaths) in fixtureSamples) {
            val rule = rules[id] ?: error("rule not loaded: $id")
            val matchers = rule.appliesTo.map { GlobUtil.matcher(it) }
            for (path in samplePaths) {
                assertTrue(
                    matchers.any { it(path) },
                    "rule '$id' appliesTo ${rule.appliesTo} should match fixture path $path",
                )
            }
        }
    }
}
