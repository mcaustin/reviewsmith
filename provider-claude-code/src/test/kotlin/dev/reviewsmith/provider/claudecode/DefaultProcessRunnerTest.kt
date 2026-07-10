package dev.reviewsmith.provider.claudecode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

@DisabledOnOs(OS.WINDOWS)
class DefaultProcessRunnerTest {

    @Test
    fun `reads stdout of a fast command`() {
        val out = DefaultProcessRunner.run(".", listOf("sh", "-c", "printf hello"), timeoutSeconds = 10)
        assertEquals("hello", out)
    }

    @Test
    fun `slow command exceeding the timeout throws AgentTimeoutException`() {
        val start = System.nanoTime()
        assertThrows(AgentTimeoutException::class.java) {
            DefaultProcessRunner.run(".", listOf("sleep", "30"), timeoutSeconds = 1)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(elapsedMs < 10_000, "should abandon promptly, not wait for the process (was ${elapsedMs}ms)")
    }

    @Test
    fun `stdin is delivered to the process`() {
        val out = DefaultProcessRunner.run(".", listOf("cat"), stdin = "piped-in", timeoutSeconds = 10)
        assertEquals("piped-in", out)
    }

    @Test
    fun `non-zero exit with budget in effect throws AgentBudgetExceededException`() {
        assertThrows(AgentBudgetExceededException::class.java) {
            DefaultProcessRunner.run(".", listOf("sh", "-c", "exit 3"), timeoutSeconds = 10, budgetInEffect = true)
        }
    }

    @Test
    fun `non-zero exit without budget in effect does not throw`() {
        val out = DefaultProcessRunner.run(".", listOf("sh", "-c", "exit 3"), timeoutSeconds = 10)
        assertEquals("", out)
    }
}
