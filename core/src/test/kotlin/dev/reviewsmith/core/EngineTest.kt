package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class EngineTest {

    private fun seedRepo(repo: Path) {
        Files.writeString(repo.resolve("A.kt"), "class A")
        Files.writeString(repo.resolve("B.kt"), "class B")
        Files.writeString(repo.resolve("notes.txt"), "ignored by include globs")
        writeRule(repo.resolve(".claude/rules"), "only-kt.md", "---\npaths:\n  - \"**/*.kt\"\n---\n# KT rule\nbody")
    }

    private fun writeRule(dir: Path, name: String, body: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(name), body)
    }

    @Test
    fun `full scan expands one work unit per rule x matching file`(@TempDir repo: Path) {
        seedRepo(repo)
        // Only the .claude/rules source, validator off, so we count exactly the rule calls.
        Files.writeString(
            repo.resolve("reviewsmith.yml"),
            """
            ruleSources:
              - .claude/rules
            validator:
              enabled: false
            """.trimIndent(),
        )
        val provider = FakeProvider()
        val engine = Engine(provider)

        val result = engine.run(repo, mode = "full")

        // 1 rule (only-kt) x 2 .kt files = 2 rule calls; notes.txt excluded by include globs.
        assertEquals(2, provider.ruleCallCount())
        assertEquals(2, result.filesReviewed)
        assertTrue(provider.requests.all { it.targetFiles.size == 1 }, "one file per unit")
    }

    @Test
    fun `findings are stamped with rule id and pass through when validator disabled`(@TempDir repo: Path) {
        seedRepo(repo)
        Files.writeString(
            repo.resolve("reviewsmith.yml"),
            "ruleSources:\n  - .claude/rules\nvalidator:\n  enabled: false",
        )
        val canned = listOf(
            Finding(ruleId = "", file = "A.kt", line = 1, severity = Severity.ERROR, message = "boom"),
        )
        val engine = Engine(FakeProvider(findingsPerRuleCall = canned))

        val result = engine.run(repo, mode = "full")

        assertTrue(result.findings.isNotEmpty())
        assertTrue(result.findings.all { it.ruleId == "only-kt" }, "ruleId stamped from the rule")
    }

    @Test
    fun `no matching files yields no findings and no calls`(@TempDir repo: Path) {
        Files.writeString(repo.resolve("notes.txt"), "no source here")
        writeRule(repo.resolve(".claude/rules"), "only-kt.md", "---\npaths:\n  - \"**/*.kt\"\n---\nbody")
        Files.writeString(repo.resolve("reviewsmith.yml"), "ruleSources:\n  - .claude/rules")
        val provider = FakeProvider()

        val result = Engine(provider).run(repo, mode = "full")

        assertEquals(0, result.filesReviewed)
        assertEquals(0, provider.ruleCallCount())
    }
}
