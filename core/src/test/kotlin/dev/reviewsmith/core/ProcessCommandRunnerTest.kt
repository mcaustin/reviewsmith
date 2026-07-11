package dev.reviewsmith.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class ProcessCommandRunnerTest {

    private fun gitAvailable(): Boolean = runCatching {
        ProcessBuilder("git", "--version").start().waitFor() == 0
    }.getOrDefault(false)

    @Test
    fun `drains output and returns promptly for a fast command`(@TempDir dir: Path) {
        assumeTrue(gitAvailable(), "git not on PATH")
        val out = ProcessCommandRunner.run(dir, listOf("git", "--version"))
        assertTrue(out.contains("git version"), "expected drained stdout, got: '$out'")
    }

    @Test
    fun `missing binary throws a descriptive error`(@TempDir dir: Path) {
        val e = runCatching {
            ProcessCommandRunner.run(dir, listOf("definitely-not-a-real-binary-xyz"))
        }.exceptionOrNull()
        assertTrue(e is RuntimeException, "expected RuntimeException, got $e")
        assertTrue(e!!.message!!.contains("PATH"), "message should mention PATH: ${e.message}")
    }
}
