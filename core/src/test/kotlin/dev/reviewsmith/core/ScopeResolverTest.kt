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
        val commitDistance: String = "3",
    ) : CommandRunner {
        val refChecks = mutableListOf<String>()
        var mergeBaseCalls = 0

        override fun run(workingDir: Path, command: List<String>): String {
            return when {
                command.firstOrNull() == "gh" -> ghPrBase
                command.contains("rev-list") -> "$commitDistance\n"
                command.contains("rev-parse") -> {
                    val ref = command.last().removeSuffix("^{commit}")
                    refChecks += ref
                    if (ref in existingRefs) "abc123\n" else ""
                }
                command.contains("merge-base") -> { mergeBaseCalls++; "$mergeBase\n" }
                command.contains("ls-files") -> untracked
                command.contains("diff") && command.contains("HEAD") && command.size == 4 -> uncommitted
                command.contains("diff") -> committed
                else -> ""
            }
        }
    }

    /** A fake git that returns canned diff text keyed by the git command shape, for captureDiffs. */
    private class DiffGit(
        val existingRefs: Set<String> = setOf("origin/main"),
        val committedDiff: String = "",
        val uncommittedDiff: String = "",
        val untrackedDiff: String = "",
        val trackedFiles: Set<String> = emptySet(),
    ) : CommandRunner {
        override fun run(workingDir: Path, command: List<String>): String = when {
            command.contains("rev-parse") -> {
                val ref = command.last().removeSuffix("^{commit}")
                if (ref in existingRefs) "abc123\n" else ""
            }
            command.contains("rev-list") -> "3\n"
            command.contains("merge-base") -> "abc1234\n"
            command.contains("--no-index") -> untrackedDiff
            command.contains("ls-files") -> if (command.last() in trackedFiles) "${command.last()}\n" else ""
            command.contains("-U5") && command.contains("HEAD") && command.contains("abc1234") -> committedDiff
            command.contains("-U5") && command.contains("HEAD") -> uncommittedDiff
            else -> ""
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
    fun `explicit baseRef branch name prefers origin over stale local`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/main", "main"), committed = "A.kt\n")
        val cfg = ReviewsmithConfig(scope = ScopeConfig(baseRef = "main"))
        ScopeResolver(git, env = { null }).resolve(repo, cfg, "changed")
        assertEquals("origin/main", git.refChecks.first(), "baseRef 'main' must try origin/main before local main")
    }

    @Test
    fun `explicit baseRef already qualified with origin is used verbatim`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/main"), committed = "A.kt\n")
        val cfg = ReviewsmithConfig(scope = ScopeConfig(baseRef = "origin/main"))
        ScopeResolver(git, env = { null }).resolve(repo, cfg, "changed")
        assertEquals("origin/main", git.refChecks.first(), "an origin/-qualified baseRef is not double-prefixed")
        assertFalse(git.refChecks.contains("origin/origin/main"), "must not produce origin/origin/main")
    }

    @Test
    fun `explicit baseRef that is a sha is used verbatim`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val sha = "abc1234"
        val git = FakeGit(existingRefs = setOf(sha), committed = "A.kt\n")
        val cfg = ReviewsmithConfig(scope = ScopeConfig(baseRef = sha))
        ScopeResolver(git, env = { null }).resolve(repo, cfg, "changed")
        assertEquals(sha, git.refChecks.first(), "a SHA baseRef is used as-is, not prefixed with origin/")
    }

    @Test
    fun `base resolution runs once across resolve and captureDiffs`(@TempDir repo: Path) {
        seed(repo, "A.kt")
        val git = FakeGit(existingRefs = setOf("origin/main"), committed = "A.kt\n")
        val resolver = ScopeResolver(git, env = { null })
        val cfg = config()
        val files = resolver.resolve(repo, cfg, "changed")
        resolver.captureDiffs(repo, cfg, files)
        assertEquals(1, git.mergeBaseCalls, "resolveBase must be memoized, not recomputed in captureDiffs")
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
    fun `captureDiffs returns the committed diff for a changed file`(@TempDir repo: Path) {
        val diff = "diff --git a/A.kt b/A.kt\n@@ -1 +1 @@\n-old\n+new"
        val git = DiffGit(committedDiff = diff)
        val ctx = ScopeResolver(git, env = { null }).captureDiffs(repo, config(), listOf("A.kt"))
        assertEquals(diff, ctx.forFile("A.kt"))
    }

    @Test
    fun `captureDiffs falls back to no-index for a new untracked file`(@TempDir repo: Path) {
        val diff = "diff --git a/New.kt b/New.kt\n@@ -0,0 +1 @@\n+brand new"
        val git = DiffGit(untrackedDiff = diff)
        val ctx = ScopeResolver(git, env = { null }).captureDiffs(repo, config(), listOf("New.kt"))
        assertEquals(diff, ctx.forFile("New.kt"))
    }

    @Test
    fun `captureDiffs yields no diff for a tracked file with no changes`(@TempDir repo: Path) {
        val git = DiffGit(trackedFiles = setOf("A.kt"), untrackedDiff = "SHOULD NOT APPEAR")
        val ctx = ScopeResolver(git, env = { null }).captureDiffs(repo, config(), listOf("A.kt"))
        assertTrue(ctx.forFile("A.kt") == null, "an unchanged tracked file must not fall back to a whole-file diff")
    }

    @Test
    fun `captureDiffs caps a huge diff at the max`(@TempDir repo: Path) {
        val huge = "+x".repeat(DiffContext.MAX_DIFF_CHARS)
        val git = DiffGit(uncommittedDiff = huge)
        val ctx = ScopeResolver(git, env = { null }).captureDiffs(repo, config(), listOf("A.kt"))
        assertEquals(DiffContext.MAX_DIFF_CHARS, ctx.forFile("A.kt")!!.length)
    }

    @Test
    fun `captureDiffs empty file list is empty context`(@TempDir repo: Path) {
        val ctx = ScopeResolver(DiffGit(), env = { null }).captureDiffs(repo, config(), emptyList())
        assertTrue(ctx.byFile.isEmpty())
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
