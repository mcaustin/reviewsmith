package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * All configs set `validator: enabled: false` — the FakeProvider returns empty for a
 * validator call, so leaving the validator on would zero findings before the baseline
 * filter runs and make these tests pass vacuously.
 */
class EngineBaselineTest {

    private fun seedRepo(repo: Path, rulePaths: String = "\"**/*.kt\"") {
        Files.writeString(repo.resolve("A.kt"), "class A")
        writeRule(repo.resolve(".claude/rules"), "r.md", "---\npaths:\n  - $rulePaths\n---\n# R\nbody")
    }

    private fun writeRule(dir: Path, name: String, body: String) {
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(name), body)
    }

    private fun config(repo: Path, extra: String = "") {
        Files.writeString(
            repo.resolve("reviewsmith.yml"),
            "ruleSources:\n  - .claude/rules\nvalidator:\n  enabled: false\n$extra",
        )
    }

    private fun canned(file: String = "A.kt", line: Int? = 1) =
        listOf(Finding(ruleId = "", file = file, line = line, severity = Severity.ERROR, message = "boom"))

    @Test
    fun `finding present in baseline is suppressed`(@TempDir repo: Path) {
        seedRepo(repo)
        config(repo)
        // Baseline the finding the FakeProvider will emit (ruleId is stamped to the rule id "r").
        BaselineWriter.write(
            listOf(Finding(ruleId = "r", file = "A.kt", line = 1, severity = Severity.ERROR, message = "boom")),
            repo.resolve("reviewsmith-baseline.json"),
            "T",
        )
        val result = Engine(FakeProvider(findingsPerRuleCall = canned())).run(repo, mode = "full")
        assertEquals(0, result.findings.size)
        assertEquals(1, result.suppressedByBaseline)
    }

    @Test
    fun `new finding not in baseline surfaces`(@TempDir repo: Path) {
        seedRepo(repo)
        config(repo)
        BaselineWriter.write(
            listOf(Finding(ruleId = "other", file = "Other.kt", severity = Severity.ERROR, message = "x")),
            repo.resolve("reviewsmith-baseline.json"),
            "T",
        )
        val result = Engine(FakeProvider(findingsPerRuleCall = canned())).run(repo, mode = "full")
        assertEquals(1, result.findings.size)
        assertEquals(0, result.suppressedByBaseline)
    }

    @Test
    fun `missing baseline file is a no-op`(@TempDir repo: Path) {
        seedRepo(repo)
        config(repo)
        val result = Engine(FakeProvider(findingsPerRuleCall = canned())).run(repo, mode = "full")
        assertEquals(1, result.findings.size)
        assertEquals(0, result.suppressedByBaseline)
    }

    @Test
    fun `baseline disabled in config skips the filter`(@TempDir repo: Path) {
        seedRepo(repo)
        config(repo, extra = "baseline:\n  enabled: false\n")
        BaselineWriter.write(
            listOf(Finding(ruleId = "r", file = "A.kt", line = 1, severity = Severity.ERROR, message = "boom")),
            repo.resolve("reviewsmith-baseline.json"),
            "T",
        )
        val result = Engine(FakeProvider(findingsPerRuleCall = canned())).run(repo, mode = "full")
        assertEquals(1, result.findings.size, "filter should be skipped when baseline.enabled=false")
        assertEquals(0, result.suppressedByBaseline)
    }

    @Test
    fun `explicit empty store bypasses the filter even when a baseline exists`(@TempDir repo: Path) {
        seedRepo(repo)
        config(repo)
        BaselineWriter.write(
            listOf(Finding(ruleId = "r", file = "A.kt", line = 1, severity = Severity.ERROR, message = "boom")),
            repo.resolve("reviewsmith-baseline.json"),
            "T",
        )
        val result = Engine(FakeProvider(findingsPerRuleCall = canned()))
            .run(repo, mode = "full", baselineStore = BaselineStore.empty())
        assertEquals(1, result.findings.size)
        assertEquals(0, result.suppressedByBaseline)
    }
}
