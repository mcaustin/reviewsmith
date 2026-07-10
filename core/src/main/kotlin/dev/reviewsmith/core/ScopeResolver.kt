package dev.reviewsmith.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

/** Runs an external command in a working dir; injectable so tests use a fake. */
fun interface CommandRunner {
    fun run(workingDir: Path, command: List<String>): String
}

object ProcessCommandRunner : CommandRunner {
    override fun run(workingDir: Path, command: List<String>): String {
        val proc = ProcessBuilder(command)
            .directory(workingDir.toFile())
            .redirectErrorStream(false)
            .start()
        val out = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return out
    }
}

class ScopeResolver(
    private val runner: CommandRunner = ProcessCommandRunner,
    private val env: (String) -> String? = System::getenv,
) {
    fun resolve(repoRoot: Path, config: ReviewsmithConfig, mode: String): List<String> {
        val files = when (mode) {
            "full" -> walkAll(repoRoot)
            else -> changedFiles(repoRoot, config.scope.baseRef)
        }
        val matchers = config.scope.include.map { GlobUtil.matcher(it) }
        return files
            .filter { rel -> matchers.any { it(rel) } }
            .filter { repoRoot.resolve(it).isRegularFile() }
            .distinct()
            .sorted()
    }

    private fun changedFiles(repoRoot: Path, configuredBaseRef: String): List<String> {
        val committed = resolveBase(repoRoot, configuredBaseRef)?.let { base ->
            runner.run(repoRoot, listOf("git", "diff", "--name-only", base, "HEAD"))
        } ?: ""
        val uncommitted = runner.run(repoRoot, listOf("git", "diff", "--name-only", "HEAD"))
        val untracked = runner.run(repoRoot, listOf("git", "ls-files", "--others", "--exclude-standard"))
        return (committed.lines() + uncommitted.lines() + untracked.lines())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Resolves a merge-base to diff against, trying the configured ref then common
     * fallbacks. Returns null if no base ref exists (e.g. a brand-new repo with no
     * remote), in which case only uncommitted changes are considered.
     */
    private fun resolveBase(repoRoot: Path, configuredBaseRef: String): String? {
        val changeTarget = env("CHANGE_TARGET")?.trim()?.takeIf { it.isNotEmpty() }
        val candidates = buildList {
            if (changeTarget != null) {
                add("origin/$changeTarget")
                add(changeTarget)
            }
            add(configuredBaseRef)
            add("origin/main")
            add("origin/master")
            add("main")
            add("master")
        }.distinct()

        for (ref in candidates) {
            if (!refExists(repoRoot, ref)) continue
            val mergeBase = runner.run(repoRoot, listOf("git", "merge-base", "HEAD", ref)).trim()
            if (mergeBase.isNotBlank()) return mergeBase
        }
        return null
    }

    private fun refExists(repoRoot: Path, ref: String): Boolean =
        runner.run(repoRoot, listOf("git", "rev-parse", "--verify", "--quiet", "$ref^{commit}"))
            .isNotBlank()

    private fun walkAll(repoRoot: Path): List<String> {
        if (!Files.exists(repoRoot)) return emptyList()
        Files.walk(repoRoot).use { stream ->
            return stream
                .filter { it.isRegularFile() }
                .filter { !it.toString().contains("/.git/") && !it.toString().contains("/build/") }
                .map { repoRoot.relativize(it).toString() }
                .toList()
        }
    }

}
