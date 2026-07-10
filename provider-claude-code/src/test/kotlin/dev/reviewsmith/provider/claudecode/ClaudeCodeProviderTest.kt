package dev.reviewsmith.provider.claudecode

import dev.reviewsmith.spi.AgentRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ClaudeCodeProviderTest {

    private fun request() = AgentRequest(
        systemPrompt = "sys",
        rulePrompt = "prompt",
        targetFiles = listOf("A.kt"),
        docRefs = emptyList(),
        projectRoot = ".",
        outputSchema = "{}",
        callTimeoutSeconds = 42,
    )

    /** A runner scripted to return a queued output (or throw) per invocation. */
    private class ScriptedRunner(private val steps: List<() -> String>) : ProcessRunner {
        var calls = 0
            private set
        val timeouts = mutableListOf<Long>()

        override fun run(
            workingDir: String,
            command: List<String>,
            stdin: String?,
            timeoutSeconds: Long,
            budgetInEffect: Boolean,
        ): String {
            timeouts.add(timeoutSeconds)
            val step = steps[calls]
            calls++
            return step()
        }
    }

    private fun envelope(findingsJson: String): String =
        """{"result": {"findings": $findingsJson}}"""

    @Test
    fun `timeout propagates and does not trigger the retry`() {
        val runner = ScriptedRunner(listOf({ throw AgentTimeoutException("boom") }))
        val provider = ClaudeCodeProvider(runner = runner)

        assertThrows(AgentTimeoutException::class.java) { provider.analyze(request()) }
        assertEquals(1, runner.calls, "timeout must not fall through to the parse-retry")
    }

    @Test
    fun `budget-exceeded propagates and does not trigger the retry`() {
        val runner = ScriptedRunner(listOf({ throw AgentBudgetExceededException("cap") }))
        val provider = ClaudeCodeProvider(runner = runner)

        assertThrows(AgentBudgetExceededException::class.java) { provider.analyze(request()) }
        assertEquals(1, runner.calls, "budget cap must not fall through to the parse-retry")
    }

    @Test
    fun `empty output retries once`() {
        val runner = ScriptedRunner(listOf({ "" }, { envelope("[]") }))
        val provider = ClaudeCodeProvider(runner = runner)

        val result = provider.analyze(request())
        assertEquals(2, runner.calls, "blank output should retry exactly once")
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `valid output after retry is returned`() {
        val good = envelope("""[{"file":"A.kt","severity":"high","message":"m"}]""")
        val runner = ScriptedRunner(listOf({ "" }, { good }))
        val provider = ClaudeCodeProvider(runner = runner)

        val result = provider.analyze(request())
        assertEquals(2, runner.calls)
        assertEquals(1, result.findings.size)
        assertEquals("A.kt", result.findings.first().file)
    }

    @Test
    fun `configured timeout is passed to the runner`() {
        val runner = ScriptedRunner(listOf({ envelope("[]") }))
        ClaudeCodeProvider(runner = runner).analyze(request())
        assertEquals(listOf(42L), runner.timeouts)
    }
}
