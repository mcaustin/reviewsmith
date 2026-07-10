package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EngineCacheTest {

    private fun canned() = listOf(
        Finding(ruleId = "", file = "A.kt", line = 1, severity = Severity.WARNING, message = "m"),
    )

    private fun seedRepo(repo: Path, cacheEnabled: Boolean = true) {
        Files.writeString(repo.resolve("A.kt"), "class A")
        Files.writeString(repo.resolve("B.kt"), "class B")
        val dir = repo.resolve(".claude/rules")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("only-kt.md"), "---\npaths:\n  - \"**/*.kt\"\n---\n# KT rule\nbody")
        Files.writeString(
            repo.resolve("reviewsmith.yml"),
            """
            ruleSources:
              - .claude/rules
            validator:
              enabled: false
            cache:
              enabled: $cacheEnabled
            """.trimIndent(),
        )
    }

    @Test
    fun `second identical run makes zero provider calls`(@TempDir repo: Path) {
        seedRepo(repo)
        val provider = FakeProvider(findingsPerRuleCall = canned(), costUsd = 0.05)

        Engine(provider).run(repo, mode = "full")
        val afterFirst = provider.ruleCallCount()
        assertEquals(2, afterFirst, "run 1 calls once per .kt file")

        val result2 = Engine(provider).run(repo, mode = "full")
        assertEquals(afterFirst, provider.ruleCallCount(), "run 2 is fully served from cache")
        assertEquals(2, result2.cacheHits)
        assertEquals(0.0, result2.totalCostUsd!!, 1e-9)
        assertTrue(result2.findings.isNotEmpty(), "cached findings still surface")
    }

    @Test
    fun `changed file content causes a miss for that unit only`(@TempDir repo: Path) {
        seedRepo(repo)
        val provider = FakeProvider(findingsPerRuleCall = canned(), costUsd = 0.05)
        Engine(provider).run(repo, mode = "full")
        val afterFirst = provider.ruleCallCount()

        Files.writeString(repo.resolve("A.kt"), "class A { }")
        val result2 = Engine(provider).run(repo, mode = "full")

        assertEquals(afterFirst + 1, provider.ruleCallCount(), "only A.kt re-runs")
        assertEquals(1, result2.cacheHits, "B.kt is still a hit")
    }

    @Test
    fun `changed rule body causes a miss`(@TempDir repo: Path) {
        seedRepo(repo)
        val provider = FakeProvider(findingsPerRuleCall = canned(), costUsd = 0.05)
        Engine(provider).run(repo, mode = "full")
        val afterFirst = provider.ruleCallCount()

        Files.writeString(
            repo.resolve(".claude/rules/only-kt.md"),
            "---\npaths:\n  - \"**/*.kt\"\n---\n# KT rule\nDIFFERENT body",
        )
        Engine(provider).run(repo, mode = "full")
        assertEquals(afterFirst + 2, provider.ruleCallCount(), "both units re-run on rule change")
    }

    @Test
    fun `changed doc content causes a miss`(@TempDir repo: Path) {
        seedRepo(repo)
        Files.writeString(repo.resolve("CLAUDE.md"), "conventions v1")
        val provider = FakeProvider(findingsPerRuleCall = canned(), costUsd = 0.05)
        Engine(provider).run(repo, mode = "full")
        val afterFirst = provider.ruleCallCount()

        Files.writeString(repo.resolve("CLAUDE.md"), "conventions v2")
        Engine(provider).run(repo, mode = "full")
        assertEquals(afterFirst + 2, provider.ruleCallCount(), "a doc edit invalidates all units")
    }

    @Test
    fun `different model causes a miss`(@TempDir repo: Path) {
        seedRepo(repo)
        Engine(FakeProvider(findingsPerRuleCall = canned(), model = "opus")).run(repo, mode = "full")

        val sonnet = FakeProvider(findingsPerRuleCall = canned(), model = "sonnet")
        val result = Engine(sonnet).run(repo, mode = "full")
        assertEquals(2, sonnet.ruleCallCount(), "a different model does not reuse another model's entries")
        assertEquals(0, result.cacheHits)
    }

    @Test
    fun `cache disabled by config makes a live call every run`(@TempDir repo: Path) {
        seedRepo(repo, cacheEnabled = false)
        val provider = FakeProvider(findingsPerRuleCall = canned(), costUsd = 0.05)
        Engine(provider).run(repo, mode = "full")
        Engine(provider).run(repo, mode = "full")
        assertEquals(4, provider.ruleCallCount(), "no caching → 2 units × 2 runs")
    }

    @Test
    fun `no-op store bypasses the cache`(@TempDir repo: Path) {
        seedRepo(repo)
        val provider = FakeProvider(findingsPerRuleCall = canned(), costUsd = 0.05)
        Engine(provider).run(repo, mode = "full", cacheStore = CacheStore.noOp())
        val result2 = Engine(provider).run(repo, mode = "full", cacheStore = CacheStore.noOp())
        assertEquals(4, provider.ruleCallCount())
        assertEquals(0, result2.cacheHits)
    }

    @Test
    fun `refresh mode re-runs and re-writes even with a warm cache`(@TempDir repo: Path) {
        seedRepo(repo)
        val provider = FakeProvider(findingsPerRuleCall = canned(), costUsd = 0.05)
        Engine(provider).run(repo, mode = "full")
        val afterWarm = provider.ruleCallCount()

        val result = Engine(provider).run(
            repo,
            mode = "full",
            cacheStore = CacheStore.refreshMode(ReviewsmithConfig.load(repo).cache, repo),
        )
        assertEquals(afterWarm + 2, provider.ruleCallCount(), "refresh bypasses reads")
        assertEquals(0, result.cacheHits)
    }

    @Test
    fun `cache disabled when provider has no model`(@TempDir repo: Path) {
        seedRepo(repo)
        val noModel = object : dev.reviewsmith.spi.AgentProvider {
            override val id = "no-model"
            override val effectiveModel: String? = null
            var calls = 0
            override fun analyze(request: dev.reviewsmith.spi.AgentRequest): dev.reviewsmith.spi.AgentResult {
                calls++
                return dev.reviewsmith.spi.AgentResult(findings = canned())
            }
        }
        Engine(noModel).run(repo, mode = "full")
        Engine(noModel).run(repo, mode = "full")
        assertEquals(4, noModel.calls, "without a model, cache is disabled and every unit runs live")
    }
}
