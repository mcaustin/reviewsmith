package dev.reviewsmith.cli

import dev.reviewsmith.core.ReviewsmithConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import picocli.CommandLine
import java.nio.file.Files
import java.nio.file.Path

class InitCommandTest {

    private fun execute(vararg args: String): Int =
        CommandLine(ReviewsmithCommand()).execute(*args)

    @Test
    fun `init without apply does not write a config file`(@TempDir repo: Path) {
        assertEquals(0, execute("init", "--root", repo.toString()))
        assertFalse(Files.exists(repo.resolve("reviewsmith.yml")), "plain init must not write files")
    }

    @Test
    fun `init --apply writes a parseable starter config`(@TempDir repo: Path) {
        assertEquals(0, execute("init", "--apply", "--root", repo.toString()))
        val path = repo.resolve("reviewsmith.yml")
        assertTrue(Files.exists(path))
        val config = ReviewsmithConfig.parse(Files.readString(path))
        assertEquals("changed", config.scope.default, "generated config must round-trip through the parser")
    }

    @Test
    fun `init --apply does not overwrite without force`(@TempDir repo: Path) {
        val path = repo.resolve("reviewsmith.yml")
        Files.writeString(path, "scope:\n  default: full\n")
        assertEquals(0, execute("init", "--apply", "--root", repo.toString()))
        assertEquals("full", ReviewsmithConfig.parse(Files.readString(path)).scope.default, "existing file preserved")
    }

    @Test
    fun `init --apply --force overwrites`(@TempDir repo: Path) {
        val path = repo.resolve("reviewsmith.yml")
        Files.writeString(path, "scope:\n  default: full\n")
        assertEquals(0, execute("init", "--apply", "--force", "--root", repo.toString()))
        assertEquals("changed", ReviewsmithConfig.parse(Files.readString(path)).scope.default, "force overwrites")
    }
}
