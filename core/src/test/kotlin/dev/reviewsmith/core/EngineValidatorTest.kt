package dev.reviewsmith.core

import dev.reviewsmith.spi.AgentProvider
import dev.reviewsmith.spi.AgentRequest
import dev.reviewsmith.spi.AgentResult
import dev.reviewsmith.spi.Confidence
import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

private class ValidatorTimeoutException(message: String) : RuntimeException(message)

/**
 * Emits one finding per rule call; every validator chunk call throws, exercising the
 * chunked validator's per-chunk AMBIGUOUS fallback. Records each validator request so the
 * test can assert chunking behavior and the timeout used.
 */
private class ValidatorFailingProvider(
    private val perRuleFindings: List<Finding>,
) : AgentProvider {
    override val id = "validator-failing"
    override val effectiveModel = "fake-model"
    override val allowedTools = "Read,Grep,Glob"

    val validatorRequests: MutableList<AgentRequest> = Collections.synchronizedList(mutableListOf())

    override fun analyze(request: AgentRequest): AgentResult {
        val isValidator = request.systemPrompt.contains("skeptical", ignoreCase = true)
        if (isValidator) {
            validatorRequests.add(request)
            throw ValidatorTimeoutException("validator timed out")
        }
        return AgentResult(findings = perRuleFindings)
    }
}

/** Rule calls return a finding; the validator echoes it back but with a hallucinated ruleId. */
private class RuleIdHallucinatingProvider(
    private val perRuleFinding: Finding,
    private val validatorRuleId: String,
) : AgentProvider {
    override val id = "hallucinating"
    override val effectiveModel = "fake-model"
    override val allowedTools = "Read,Grep,Glob"

    override fun analyze(request: AgentRequest): AgentResult {
        val isValidator = request.systemPrompt.contains("skeptical", ignoreCase = true)
        return if (isValidator) {
            AgentResult(
                findings = listOf(
                    perRuleFinding.copy(ruleId = validatorRuleId, confidence = Confidence.CLEAR),
                ),
            )
        } else {
            AgentResult(findings = listOf(perRuleFinding))
        }
    }
}

class EngineValidatorTest {

    @Test
    fun `validator cannot rewrite a findings ruleId to an unknown value`(@TempDir repo: Path) {
        Files.writeString(repo.resolve("F1.kt"), "class F1")
        val dir = repo.resolve(".claude/rules")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("only-kt.md"), "---\npaths:\n  - \"**/*.kt\"\n---\n# KT\nbody")
        Files.writeString(repo.resolve("reviewsmith.yml"), "ruleSources:\n  - .claude/rules\n")

        val ruleFinding = Finding(ruleId = "", file = "F1.kt", line = 3, severity = Severity.ERROR, message = "boom")
        val provider = RuleIdHallucinatingProvider(ruleFinding, validatorRuleId = "totally-made-up")

        val result = Engine(provider).run(repo, mode = "full")

        assertTrue(result.findings.isNotEmpty())
        assertTrue(
            result.findings.none { it.ruleId == "totally-made-up" },
            "hallucinated ruleId must be corrected back to the input rule: ${result.findings.map { it.ruleId }}",
        )
        assertEquals("only-kt", result.findings.first().ruleId)
    }

    private fun seedRepo(repo: Path, files: Int, validatorYaml: String) {
        for (i in 1..files) Files.writeString(repo.resolve("F$i.kt"), "class F$i")
        val dir = repo.resolve(".claude/rules")
        Files.createDirectories(dir)
        Files.writeString(dir.resolve("only-kt.md"), "---\npaths:\n  - \"**/*.kt\"\n---\n# KT\nbody")
        Files.writeString(
            repo.resolve("reviewsmith.yml"),
            "ruleSources:\n  - .claude/rules\n$validatorYaml",
        )
    }

    @Test
    fun `abandoned validator chunk keeps findings as AMBIGUOUS not null`(@TempDir repo: Path) {
        seedRepo(repo, files = 2, validatorYaml = "validator:\n  timeoutSeconds: 5\n  chunkSize: 20")
        val finding = Finding(ruleId = "", file = "F.kt", line = 1, severity = Severity.ERROR, message = "boom")
        val provider = ValidatorFailingProvider(listOf(finding))

        val result = Engine(provider).run(repo, mode = "full")

        assertTrue(result.findings.isNotEmpty(), "findings survive an abandoned validator")
        assertTrue(
            result.findings.all { it.confidence == Confidence.AMBIGUOUS },
            "abandoned-validator findings are AMBIGUOUS, never null: ${result.findings.map { it.confidence }}",
        )
    }

    @Test
    fun `validator chunk gets the configured validator timeout`(@TempDir repo: Path) {
        seedRepo(repo, files = 1, validatorYaml = "validator:\n  timeoutSeconds: 42\n  chunkSize: 20")
        val provider = ValidatorFailingProvider(
            listOf(Finding(ruleId = "", file = "F.kt", line = 1, severity = Severity.WARNING, message = "m")),
        )

        Engine(provider).run(repo, mode = "full")

        assertTrue(provider.validatorRequests.isNotEmpty())
        assertTrue(
            provider.validatorRequests.all { it.callTimeoutSeconds == 42L },
            "validator uses validator.timeoutSeconds, not the rule timeout",
        )
    }

    @Test
    fun `findings are split across validator chunks by chunkSize`(@TempDir repo: Path) {
        // 4 files x 1 rule = 4 findings; chunkSize 2 -> 2 validator chunk calls.
        seedRepo(repo, files = 4, validatorYaml = "validator:\n  timeoutSeconds: 5\n  chunkSize: 2")
        val provider = ValidatorFailingProvider(
            listOf(Finding(ruleId = "", file = "F.kt", line = 1, severity = Severity.ERROR, message = "m")),
        )

        Engine(provider).run(repo, mode = "full")

        assertEquals(2, provider.validatorRequests.size, "4 findings / chunkSize 2 = 2 validator calls")
    }
}
