package dev.reviewsmith.cli

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Path

class MainTest {

    private fun execute(vararg args: String): Int =
        CommandLine(ReviewsmithCommand()).execute(*args)

    @Test
    fun `list-rules exits 0 without calling the agent`(@TempDir repo: Path) {
        assertEquals(0, execute("--list-rules", "--root", repo.toString()))
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
