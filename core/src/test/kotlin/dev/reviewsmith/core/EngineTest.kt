package dev.reviewsmith.core

import dev.reviewsmith.spi.AgentProvider
import dev.reviewsmith.spi.AgentRequest
import dev.reviewsmith.spi.AgentResult
import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

private class TimeoutException(message: String) : RuntimeException(message)

/** Throws on rule calls for files whose name is in [failFiles]; echoes findings otherwise. */
private class PartialFailProvider(
    private val failFiles: Set<String>,
    private val findings: List<Finding>,
    private val failValidator: Boolean = false,
) : AgentProvider {
    override val id = "partial-fail"

    override fun analyze(request: AgentRequest): AgentResult {
        val isValidator = request.systemPrompt.contains("skeptical", ignoreCase = true)
        if (isValidator) {
            if (failValidator) throw TimeoutException("validator timed out")
            return AgentResult(findings = emptyList())
        }
        if (request.targetFiles.any { it.substringAfterLast('/') in failFiles }) {
            throw TimeoutException("unit timed out")
        }
        return AgentResult(findings = findings)
    }
}

/**
 * One rule unit (for [slowFile]) blocks on a latch; every other unit returns [findings]
 * immediately. When a validator call arrives, it records whether the slow unit is still
 * blocked (proving overlap) and then releases the latch so the run can finish.
 */
private class OverlapProbeProvider(
    private val slowFile: String,
    private val findings: List<Finding>,
) : AgentProvider {
    override val id = "overlap-probe"
    private val latch = java.util.concurrent.CountDownLatch(1)
    private val slowUnitFinished = java.util.concurrent.atomic.AtomicBoolean(false)
    val validatorStartedBeforeSlowUnitFinished = java.util.concurrent.atomic.AtomicBoolean(false)

    override fun analyze(request: AgentRequest): AgentResult {
        val isValidator = request.systemPrompt.contains("skeptical", ignoreCase = true)
        if (isValidator) {
            if (!slowUnitFinished.get()) validatorStartedBeforeSlowUnitFinished.set(true)
            latch.countDown()
            return AgentResult(findings = emptyList())
        }
        if (request.targetFiles.any { it.substringAfterLast('/') == slowFile }) {
            latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
            slowUnitFinished.set(true)
        }
        return AgentResult(findings = findings)
    }
}

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
    fun `total cost accumulates across rule units`(@TempDir repo: Path) {
        seedRepo(repo)
        Files.writeString(
            repo.resolve("reviewsmith.yml"),
            "ruleSources:\n  - .claude/rules\nvalidator:\n  enabled: false",
        )
        val provider = FakeProvider(durationMs = 1200, costUsd = 0.05)

        val result = Engine(provider).run(repo, mode = "full")

        // 1 rule x 2 .kt files = 2 units, each $0.05.
        assertEquals(0.10, result.totalCostUsd!!, 1e-9)
    }

    @Test
    fun `a failing unit is isolated and counted as abandoned`(@TempDir repo: Path) {
        seedRepo(repo)
        Files.writeString(
            repo.resolve("reviewsmith.yml"),
            "ruleSources:\n  - .claude/rules\nvalidator:\n  enabled: false",
        )
        val canned = listOf(
            Finding(ruleId = "", file = "B.kt", line = 1, severity = Severity.WARNING, message = "ok"),
        )
        val provider = PartialFailProvider(failFiles = setOf("A.kt"), findings = canned)

        val result = Engine(provider).run(repo, mode = "full")

        assertEquals(1, result.abandonedUnits, "the A.kt unit is abandoned")
        assertTrue(result.findings.isNotEmpty(), "B.kt findings survive")
        assertTrue(result.findings.all { it.file == "B.kt" })
    }

    @Test
    fun `validator timeout keeps raw findings instead of crashing`(@TempDir repo: Path) {
        seedRepo(repo)
        Files.writeString(repo.resolve("reviewsmith.yml"), "ruleSources:\n  - .claude/rules")
        val canned = listOf(
            Finding(ruleId = "", file = "A.kt", line = 1, severity = Severity.ERROR, message = "boom"),
        )
        val provider = PartialFailProvider(failFiles = emptySet(), findings = canned, failValidator = true)

        val result = Engine(provider).run(repo, mode = "full")

        assertTrue(result.findings.isNotEmpty(), "raw findings are kept when the validator times out")
        assertEquals(0, result.abandonedUnits, "validator failure is not a unit abandonment")
    }

    @Test
    fun `run result carries rulesById and modelId`(@TempDir repo: Path) {
        seedRepo(repo)
        Files.writeString(repo.resolve("reviewsmith.yml"), "ruleSources:\n  - .claude/rules\nvalidator:\n  enabled: false")
        val result = Engine(FakeProvider()).run(repo, mode = "full")
        assertTrue(result.rulesById.containsKey("only-kt"), "rulesById maps rule ids")
        assertEquals("fake-model", result.modelId)
    }

    @Test
    fun `empty-files early return still carries rulesById and modelId`(@TempDir repo: Path) {
        Files.writeString(repo.resolve("notes.txt"), "no source")
        writeRule(repo.resolve(".claude/rules"), "only-kt.md", "---\npaths:\n  - \"**/*.kt\"\n---\nbody")
        Files.writeString(repo.resolve("reviewsmith.yml"), "ruleSources:\n  - .claude/rules")
        val result = Engine(FakeProvider()).run(repo, mode = "full")
        assertEquals(0, result.filesReviewed)
        assertTrue(result.rulesById.containsKey("only-kt"))
        assertEquals("fake-model", result.modelId)
    }

    @Test
    fun `per-rule docs are merged into the request and prompt`(@TempDir repo: Path) {
        Files.writeString(repo.resolve("A.kt"), "class A")
        writeRule(
            repo.resolve(".claude/rules"),
            "with-docs.md",
            "---\npaths:\n  - \"**/*.kt\"\ndocs:\n  - \"internal/guide.md\"\n---\n# rule\nbody",
        )
        Files.writeString(
            repo.resolve("reviewsmith.yml"),
            "ruleSources:\n  - .claude/rules\nvalidator:\n  enabled: false",
        )
        val provider = FakeProvider()

        Engine(provider).run(repo, mode = "full")

        val ruleRequest = provider.requests.single()
        assertTrue(ruleRequest.docRefs.contains("internal/guide.md"), "rule.docs reaches docRefs")
        assertTrue(ruleRequest.rulePrompt.contains("internal/guide.md"), "rule.docs reaches the prompt")
    }

    @Test
    fun `rule callTimeoutSeconds reaches the agent request`(@TempDir repo: Path) {
        Files.writeString(repo.resolve("A.kt"), "class A")
        writeRule(
            repo.resolve(".claude/rules"),
            "capped.md",
            "---\npaths:\n  - \"**/*.kt\"\ncallTimeoutSeconds: 90\n---\nbody",
        )
        Files.writeString(
            repo.resolve("reviewsmith.yml"),
            "ruleSources:\n  - .claude/rules\nvalidator:\n  enabled: false\ncallTimeoutSeconds: 300",
        )
        val provider = FakeProvider()

        Engine(provider).run(repo, mode = "full")

        assertEquals(90L, provider.requests.single().callTimeoutSeconds, "rule timeout overrides the global one")
    }

    @Test
    fun `validator overlaps the rule tail instead of waiting for the barrier`(@TempDir repo: Path) {
        for (i in 1..6) Files.writeString(repo.resolve("F$i.kt"), "class F$i")
        writeRule(repo.resolve(".claude/rules"), "only-kt.md", "---\npaths:\n  - \"**/*.kt\"\n---\nbody")
        Files.writeString(
            repo.resolve("reviewsmith.yml"),
            "ruleSources:\n  - .claude/rules\nvalidator:\n  chunkSize: 1\nmaxConcurrency: 6",
        )
        val canned = listOf(Finding(ruleId = "", file = "F.kt", line = 1, severity = Severity.ERROR, message = "m"))
        val provider = OverlapProbeProvider(slowFile = "F1.kt", findings = canned)

        Engine(provider).run(repo, mode = "full")

        assertTrue(
            provider.validatorStartedBeforeSlowUnitFinished.get(),
            "a validator chunk should run while the slow rule unit is still blocked",
        )
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
