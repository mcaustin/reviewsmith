package dev.reviewsmith.provider.claudecode

import dev.reviewsmith.spi.AgentRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

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
        val budgets = mutableListOf<Boolean>()
        var lastCommand: List<String> = emptyList()
            private set

        override fun run(
            workingDir: String,
            command: List<String>,
            stdin: String?,
            timeoutSeconds: Long,
            budgetInEffect: Boolean,
        ): String {
            timeouts.add(timeoutSeconds)
            budgets.add(budgetInEffect)
            lastCommand = command
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
    fun `suggestedFix is parsed when present and null when absent`() {
        val out = envelope(
            """[
                {"file":"A.kt","severity":"high","message":"m","suggestedFix":"use coerceAtMost(cap)"},
                {"file":"B.kt","severity":"low","message":"n"}
            ]""",
        )
        val result = ClaudeCodeProvider(runner = ScriptedRunner(listOf({ out }))).analyze(request())
        assertEquals("use coerceAtMost(cap)", result.findings[0].suggestedFix)
        assertNull(result.findings[1].suggestedFix)
    }

    @Test
    fun `configured timeout is passed to the runner`() {
        val runner = ScriptedRunner(listOf({ envelope("[]") }))
        ClaudeCodeProvider(runner = runner).analyze(request())
        assertEquals(listOf(42L), runner.timeouts)
    }

    @Test
    fun `duration cost and usage are extracted from the envelope`() {
        val full = """
            {"duration_ms": 4210, "total_cost_usd": 0.1307,
             "usage": {"input_tokens": 5000, "output_tokens": 12, "cache_read_input_tokens": 800},
             "result": {"findings": []}}
        """.trimIndent()
        val result = ClaudeCodeProvider(runner = ScriptedRunner(listOf({ full }))).analyze(request())

        assertEquals(4210L, result.durationMs)
        assertEquals(0.1307, result.costUsd!!, 1e-9)
        assertEquals(5000L, result.usage?.inputTokens)
        assertEquals(12L, result.usage?.outputTokens)
        assertEquals(800L, result.usage?.cacheReadInputTokens)
    }

    @Test
    fun `missing envelope telemetry fields yield nulls`() {
        val result = ClaudeCodeProvider(runner = ScriptedRunner(listOf({ envelope("[]") }))).analyze(request())
        assertNull(result.durationMs)
        assertNull(result.costUsd)
        assertNull(result.usage)
    }

    @Test
    fun `cost is summed across a retry`() {
        val first = """{"total_cost_usd": 0.02, "duration_ms": 1000, "result": "not-json-so-parse-fails"}"""
        val second = """{"total_cost_usd": 0.05, "duration_ms": 3000, "result": {"findings": []}}"""
        val result = ClaudeCodeProvider(runner = ScriptedRunner(listOf({ first }, { second }))).analyze(request())

        assertEquals(0.07, result.costUsd!!, 1e-9, "both attempts' cost should be summed")
        assertEquals(3000L, result.durationMs, "duration should be the max across attempts")
    }

    @Test
    fun `a top-level JSON array does not crash telemetry extraction`() {
        val result = ClaudeCodeProvider(runner = ScriptedRunner(listOf({ "[]" }, { envelope("[]") }))).analyze(request())
        assertNull(result.durationMs)
        assertNull(result.costUsd)
    }

    @Test
    fun `max-budget-usd flag is added with fixed-decimal format when set`() {
        val runner = ScriptedRunner(listOf({ envelope("[]") }))
        ClaudeCodeProvider(runner = runner).analyze(request().copy(maxBudgetUsd = 0.05))

        val cmd = runner.lastCommand
        val i = cmd.indexOf("--max-budget-usd")
        assertTrue(i >= 0, "flag present: $cmd")
        assertEquals("0.050000", cmd[i + 1], "fixed-decimal, no scientific notation")
        assertEquals(listOf(true), runner.budgets)
    }

    @Test
    fun `no max-budget-usd flag when unset`() {
        val runner = ScriptedRunner(listOf({ envelope("[]") }))
        ClaudeCodeProvider(runner = runner).analyze(request())

        assertFalse(runner.lastCommand.contains("--max-budget-usd"))
        assertEquals(listOf(false), runner.budgets)
    }

    @Test
    fun `small budget avoids scientific notation`() {
        val runner = ScriptedRunner(listOf({ envelope("[]") }))
        ClaudeCodeProvider(runner = runner).analyze(request().copy(maxBudgetUsd = 0.0000001))

        val cmd = runner.lastCommand
        assertEquals("0.000000", cmd[cmd.indexOf("--max-budget-usd") + 1])
    }

    @Test
    fun `hermetic true adds safe-mode`() {
        val runner = ScriptedRunner(listOf({ envelope("[]") }))
        ClaudeCodeProvider(runner = runner, hermetic = true).analyze(request())
        assertTrue(runner.lastCommand.contains("--safe-mode"), runner.lastCommand.toString())
    }

    @Test
    fun `hermetic false omits safe-mode`() {
        val runner = ScriptedRunner(listOf({ envelope("[]") }))
        ClaudeCodeProvider(runner = runner, hermetic = false).analyze(request())
        assertFalse(runner.lastCommand.contains("--safe-mode"))
    }

    @Test
    fun `default provider is hermetic`() {
        val runner = ScriptedRunner(listOf({ envelope("[]") }))
        ClaudeCodeProvider(runner = runner).analyze(request())
        assertTrue(runner.lastCommand.contains("--safe-mode"))
    }

    @Test
    fun `budget uses a dot decimal separator under a comma-decimal locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val runner = ScriptedRunner(listOf({ envelope("[]") }))
            ClaudeCodeProvider(runner = runner).analyze(request().copy(maxBudgetUsd = 0.05))

            val cmd = runner.lastCommand
            assertEquals("0.050000", cmd[cmd.indexOf("--max-budget-usd") + 1])
        } finally {
            Locale.setDefault(original)
        }
    }
}
