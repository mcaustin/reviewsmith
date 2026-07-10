package dev.reviewsmith.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class ScopeResolverTest {

    /**
     * A fake git that answers the ScopeResolver's queries from canned per-command output and
     * records which refs were probed for existence.
     */
    private class FakeGit(
        val existingRefs: Set<String> = emptySet(),
        val committed: String = "",
        val uncommitted: String = "",
        val untracked: String = "",
        val mergeBase: String = "BASE",
    ) : CommandRunner {
        val refChecks = mutableListOf<String>()

        override fun run(workingDir: Path, command: List<String>): String {
            return when {
                command.contains("rev-parse") -> {
                    val ref = command.last().removeSuffix("^{commit}")
                    refChecks += ref
                    if (ref in existingRefs) "abc123\n" else ""
                }
                command.contains("merge-base") -> "$mergeBase\n"
                command.contains("ls-files") -> untracked
                command.contains("diff") && command.contains("HEAD") && command.size == 4 -> uncommitted
                command.contains("diff") -> committed
                else -> ""
            }
        }
    }

    private fun seed(repo: Path, vararg rels: String) {
        for (rel in rels) {
            val p = repo.resolve(rel)
            Files.createDirectories(p.parent)
            Files.writeString(p, "x")
        }
    }

    private fun config() = ReviewsmithConfig()

    @Test
    fun `untracked files are included in changed scope`(@TempDir repo: Path) {
        seed(repo, "New.kt", "Tracked.kt")
        val git = FakeGit(
            existingRefs = setOf("origin/main"),
            uncommitted = "Tracked.kt\n",
            untracked = "New.kt\n",
        )
        val files = ScopeResolver(git, env = { null }).resolve(repo, config(), "changed")
        assertTrue(files.contains("New.kt"), "untracked new file must be reviewed: $files")
        assertTrue(files.contains("Tracked.kt"))
    }

    @Test
    fun `untracked non-source files are excluded by include globs`(@TempDir repo: Path) {
        seed(repo, "New.kt", "notes.txt")
        val git = FakeGit(existingRefs = setOf("origin/main"), untracked = "New.kt\nnotes.txt\n")
        val files = ScopeResolver(git, env = { null }).resolve(repo, config(), "changed")
        assertEquals(listOf("New.kt"), files)
    }

    @Test
    fun `reported files that no longer exist are dropped`(@TempDir repo: Path) {
        seed(repo, "Present.kt")
        val git = FakeGit(existingRefs = setOf("origin/main"), committed = "Present.kt\nDeleted.kt\n")
        val files = ScopeResolver(git, env = { null }).resolve(repo, config(), "changed")
        assertEquals(listOf("Present.kt"), files)
    }

    @Test
    fun `CHANGE_TARGET is preferred as the base ref`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/release-9", "origin/main"), committed = "A.kt\n")
        ScopeResolver(git, env = { if (it == "CHANGE_TARGET") "release-9" else null })
            .resolve(repo, config(), "changed")
        assertEquals("origin/release-9", git.refChecks.first(), "CHANGE_TARGET should be tried first")
    }

    @Test
    fun `CHANGE_TARGET unset falls back to origin main`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/main"), committed = "A.kt\n")
        ScopeResolver(git, env = { null }).resolve(repo, config(), "changed")
        assertFalse(git.refChecks.isEmpty())
        assertEquals("origin/main", git.refChecks.first { it in setOf("origin/main") })
    }

    @Test
    fun `no base ref still returns uncommitted and untracked`(@TempDir repo: Path) {
        seed(repo, "Local.kt", "New.kt")
        val git = FakeGit(existingRefs = emptySet(), uncommitted = "Local.kt\n", untracked = "New.kt\n")
        val files = ScopeResolver(git, env = { null }).resolve(repo, config(), "changed")
        assertEquals(listOf("Local.kt", "New.kt"), files)
    }
}
