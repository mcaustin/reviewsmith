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
        val mergeBase: String = "abc1234",
        val ghPrBase: String = "",
    ) : CommandRunner {
        val refChecks = mutableListOf<String>()

        override fun run(workingDir: Path, command: List<String>): String {
            return when {
                command.firstOrNull() == "gh" -> ghPrBase
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

    @Test
    fun `explicit config baseRef wins over everything`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/release-1", "origin/main"), committed = "A.kt\n", ghPrBase = "some-pr-base")
        val cfg = ReviewsmithConfig(scope = ScopeConfig(baseRef = "origin/release-1"))
        ScopeResolver(git, env = { "env-branch" }).resolve(repo, cfg, "changed")
        assertEquals("origin/release-1", git.refChecks.first(), "config baseRef must be tried first")
    }

    @Test
    fun `GITHUB_BASE_REF detected as PR base`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/feature-parent", "origin/main"), committed = "A.kt\n")
        ScopeResolver(git, env = { if (it == "GITHUB_BASE_REF") "feature-parent" else null })
            .resolve(repo, config(), "changed")
        assertEquals("origin/feature-parent", git.refChecks.first())
    }

    @Test
    fun `gh PR base auto-detected for a stacked PR`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/parent-pr", "origin/main"), committed = "A.kt\n", ghPrBase = "parent-pr\n")
        ScopeResolver(git, env = { null }).resolve(repo, config(), "changed")
        assertEquals("origin/parent-pr", git.refChecks.first(), "gh PR base should be tried before main")
    }

    @Test
    fun `gh failure falls through to origin main`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/main"), committed = "A.kt\n", ghPrBase = "")
        ScopeResolver(git, env = { null }).resolve(repo, config(), "changed")
        assertEquals("origin/main", git.refChecks.first { it in setOf("origin/main") })
    }

    @Test
    fun `detectBase false skips gh probe`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/parent-pr", "origin/main"), committed = "A.kt\n", ghPrBase = "parent-pr\n")
        val cfg = ReviewsmithConfig(scope = ScopeConfig(detectBase = false))
        ScopeResolver(git, env = { null }).resolve(repo, cfg, "changed")
        assertFalse(git.refChecks.contains("origin/parent-pr"), "gh base must not be consulted when detectBase=false")
        assertEquals("origin/main", git.refChecks.first { it in setOf("origin/main") })
    }

    @Test
    fun `normal PR to main is unchanged when no detection signals`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/main"), committed = "A.kt\n")
        val files = ScopeResolver(git, env = { null }).resolve(repo, config(), "changed")
        assertEquals(listOf("A.kt"), files)
        assertEquals("origin/main", git.refChecks.first { it in setOf("origin/main") })
    }

    @Test
    fun `non-sha merge-base output is not treated as a base`(@TempDir repo: Path) {
        seed(repo, "Committed.kt", "Local.kt")
        val git = FakeGit(
            existingRefs = setOf("origin/main"),
            committed = "Committed.kt\n",
            uncommitted = "Local.kt\n",
            mergeBase = "fatal: refusing to merge unrelated histories",
        )
        val files = ScopeResolver(git, env = { null }).resolve(repo, config(), "changed")
        assertEquals(listOf("Local.kt"), files, "a git error on merge-base must not become the diff base")
    }

    @Test
    fun `unknown scope falls back to changed`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/main"), committed = "A.kt\n")
        val files = ScopeResolver(git, env = { null }).resolve(repo, config(), "bogus")
        assertEquals(listOf("A.kt"), files)
    }

    @Test
    fun `full scope excludes the reviewsmith cache directory`(@TempDir repo: Path) {
        seed(repo, "Src.kt", ".reviewsmith/cache/x.kt")
        val cfg = ReviewsmithConfig(scope = ScopeConfig(include = listOf("**/*.kt")))
        val files = ScopeResolver(FakeGit(), env = { null }).resolve(repo, cfg, "full")
        assertTrue(files.contains("Src.kt"))
        assertFalse(files.any { it.contains(".reviewsmith") }, "cache dir must be excluded: $files")
    }
}
