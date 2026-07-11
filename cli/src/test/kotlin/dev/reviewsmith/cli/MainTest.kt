package dev.reviewsmith.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path

class MainTest {

    private fun execute(vararg args: String): Int =
        CommandLine(ReviewsmithCommand()).execute(*args)

    private fun captureOut(vararg args: String): Pair<Int, String> {
        val original = System.out
        val buf = java.io.ByteArrayOutputStream()
        System.setOut(java.io.PrintStream(buf))
        try {
            val code = execute(*args)
            return code to buf.toString()
        } finally {
            System.setOut(original)
        }
    }

    private fun seedRepo(repo: Path) {
        Files.writeString(repo.resolve("A.kt"), "class A")
        Files.writeString(repo.resolve("B.kt"), "class B")
        val rules = repo.resolve(".claude/rules")
        Files.createDirectories(rules)
        Files.writeString(rules.resolve("only-kt.md"), "---\npaths:\n  - \"**/*.kt\"\n---\nbody")
        Files.writeString(repo.resolve("reviewsmith.yml"), "ruleSources:\n  - .claude/rules")
    }

    @Test
    fun `list-rules exits 0 without calling the agent`(@TempDir repo: Path) {
        assertEquals(0, execute("--list-rules", "--root", repo.toString()))
    }

    @Test
    fun `dry-run reports scope and cost without calling the agent`(@TempDir repo: Path) {
        seedRepo(repo)
        val (code, out) = captureOut("--dry-run", "--scope", "full", "--root", repo.toString())
        assertEquals(0, code)
        assertTrue(out.contains("2 file(s)"), "expected 2 files in: $out")
        assertTrue(out.contains("work unit(s)"), "expected unit count in: $out")
        assertTrue(out.contains("No agent calls made."), out)
    }

    @Test
    fun `--rule filter narrows list-rules to the named rule`(@TempDir repo: Path) {
        val (code, out) = captureOut("--list-rules", "--rule", "correctness-safety", "--root", repo.toString())
        assertEquals(0, code)
        assertTrue(out.contains("correctness-safety"), out)
        assertFalse(out.contains("pii-logging"), "other rules must be filtered out: $out")
    }

    @Test
    fun `unknown option exits 2`(@TempDir repo: Path) {
        assertEquals(2, execute("--definitely-not-a-flag", "--root", repo.toString()))
    }

    @Test
    fun `help exits 0`() {
        assertEquals(0, execute("--help"))
    }
}
