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
            else -> changedFiles(repoRoot, config.scope)
        }
        val matchers = config.scope.include.map { GlobUtil.matcher(it) }
        return files
            .filter { rel -> matchers.any { it(rel) } }
            .filter { repoRoot.resolve(it).isRegularFile() }
            .distinct()
            .sorted()
    }

    private fun changedFiles(repoRoot: Path, scope: ScopeConfig): List<String> {
        val committed = resolveBase(repoRoot, scope)?.let { base ->
            runner.run(repoRoot, listOf("git", "diff", "--name-only", base, "HEAD"))
        } ?: ""
        val uncommitted = runner.run(repoRoot, listOf("git", "diff", "--name-only", "HEAD"))
        val untracked = runner.run(repoRoot, listOf("git", "ls-files", "--others", "--exclude-standard"))
        return (committed.lines() + uncommitted.lines() + untracked.lines())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    /**
     * Resolves a merge-base to diff against. Candidate base branches are gathered
     * most-authoritative-first — an explicit [ScopeConfig.baseRef], then CI env vars, then
     * the PR base reported by `gh`, then the usual main/master fallbacks — so a PR stacked
     * on another branch is diffed against its real parent rather than main. Returns null if
     * none resolve (e.g. a brand-new repo with no remote), leaving only local changes.
     */
    private fun resolveBase(repoRoot: Path, scope: ScopeConfig): String? {
        val candidates = baseCandidates(repoRoot, scope)
        for ((ref, source) in candidates) {
            if (!refExists(repoRoot, ref)) continue
            val mergeBase = runner.run(repoRoot, listOf("git", "merge-base", "HEAD", ref)).trim()
            if (mergeBase.isNotBlank()) {
                System.err.println("Reviewsmith: reviewing changes vs $ref (base from $source)")
                return mergeBase
            }
        }
        return null
    }

    /** Ordered (ref, source) candidates; a branch name yields both `origin/<name>` and `<name>`. */
    private fun baseCandidates(repoRoot: Path, scope: ScopeConfig): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun addBranch(name: String, source: String) {
            out += "origin/$name" to source
            out += name to source
        }

        scope.baseRef?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it to "config" }

        ciBaseBranch()?.let { (name, source) -> addBranch(name, source) }

        if (scope.detectBase) ghPrBaseBranch(repoRoot)?.let { addBranch(it, "gh PR") }

        addBranch("main", "fallback")
        addBranch("master", "fallback")
        return out.distinct()
    }

    private fun ciBaseBranch(): Pair<String, String>? {
        val vars = listOf(
            "GITHUB_BASE_REF" to "GITHUB_BASE_REF",
            "CHANGE_TARGET" to "CHANGE_TARGET",
            "CI_MERGE_REQUEST_TARGET_BRANCH_NAME" to "CI_MERGE_REQUEST_TARGET_BRANCH_NAME",
        )
        for ((key, source) in vars) {
            env(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it to source }
        }
        return null
    }

    /** Best-effort PR base via `gh`; returns null if gh is missing, unauthed, or no PR. */
    private fun ghPrBaseBranch(repoRoot: Path): String? =
        runCatching {
            runner.run(repoRoot, listOf("gh", "pr", "view", "--json", "baseRefName", "-q", ".baseRefName"))
                .trim().takeIf { it.isNotEmpty() && !it.contains(' ') }
        }.getOrNull()

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
