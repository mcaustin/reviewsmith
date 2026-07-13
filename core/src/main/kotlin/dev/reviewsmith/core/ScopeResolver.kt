package dev.reviewsmith.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.io.path.isRegularFile

/** Runs an external command in a working dir; injectable so tests use a fake. */
fun interface CommandRunner {
    fun run(workingDir: Path, command: List<String>): String
}

private val COMMIT_SHA = Regex("^[0-9a-f]{7,40}$")

object ProcessCommandRunner : CommandRunner {
    private const val GIT_TIMEOUT_SECONDS = 120L

    override fun run(workingDir: Path, command: List<String>): String {
        val proc = try {
            ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw RuntimeException(
                "'${command.firstOrNull() ?: "command"}' could not be launched " +
                    "(is git installed and on PATH?): ${e.message}",
                e,
            )
        }

        val outHolder = AtomicReference("")
        val outThread = Thread {
            outHolder.set(proc.inputStream.bufferedReader().readText())
        }.apply { isDaemon = true; start() }

        val finished = proc.waitFor(GIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        if (!finished) {
            proc.toHandle().descendants().forEach { it.destroyForcibly() }
            proc.destroyForcibly()
            throw RuntimeException(
                "'${command.joinToString(" ")}' timed out after ${GIT_TIMEOUT_SECONDS}s",
            )
        }
        outThread.join(TimeUnit.SECONDS.toMillis(5))
        return outHolder.get()
    }
}

class ScopeResolver(
    private val runner: CommandRunner = ProcessCommandRunner,
    private val env: (String) -> String? = System::getenv,
) {
    private var cachedBase: Pair<ScopeConfig, String?>? = null

    private companion object {
        const val STALE_BASE_WARN_COMMITS = 50
    }
    fun resolve(repoRoot: Path, config: ReviewsmithConfig, mode: String): List<String> {
        val files = when (mode) {
            "full" -> walkAll(repoRoot)
            "changed" -> changedFiles(repoRoot, config.scope)
            else -> {
                System.err.println("Reviewsmith: unknown scope '$mode', using 'changed'.")
                changedFiles(repoRoot, config.scope)
            }
        }
        val matchers = config.scope.include.map { GlobUtil.matcher(it) }
        return files
            .filter { rel -> matchers.any { it(rel) } }
            .filter { repoRoot.resolve(it).isRegularFile() }
            .distinct()
            .sorted()
    }

    /**
     * Captures a unified diff (±5 lines of context) for each of [files] so the rule and
     * validator prompts can show the actual changed lines. Committed changes diff against the
     * resolved base, uncommitted changes against HEAD, and a file absent from both (a new
     * untracked file) is diffed against `/dev/null` so its whole body shows as additions. A
     * per-file diff is capped at [DiffContext.MAX_DIFF_CHARS] and any capture failure degrades
     * to no diff for that file rather than failing the run.
     */
    fun captureDiffs(repoRoot: Path, config: ReviewsmithConfig, files: List<String>): DiffContext {
        if (files.isEmpty()) return DiffContext.EMPTY
        val base = resolveBase(repoRoot, config.scope)
        val byFile = LinkedHashMap<String, String>()
        for (file in files) {
            val diff = runCatching { captureFileDiff(repoRoot, base, file) }.getOrNull()
            if (!diff.isNullOrBlank()) byFile[file] = diff.take(DiffContext.MAX_DIFF_CHARS)
        }
        return DiffContext(byFile)
    }

    private fun captureFileDiff(repoRoot: Path, base: String?, file: String): String {
        val committed = base?.let {
            runner.run(repoRoot, listOf("git", "diff", "-U5", it, "HEAD", "--", file))
        }.orEmpty()
        val uncommitted = runner.run(repoRoot, listOf("git", "diff", "-U5", "HEAD", "--", file))
        val tracked = (committed + "\n" + uncommitted).trim()
        if (tracked.isNotEmpty()) return tracked
        val isTracked = runner.run(repoRoot, listOf("git", "ls-files", "--", file)).isNotBlank()
        if (isTracked) return ""
        return runner.run(repoRoot, listOf("git", "diff", "-U5", "--no-index", "--", "/dev/null", file)).trim()
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
        cachedBase?.let { (cachedScope, base) -> if (cachedScope == scope) return base }
        val resolved = computeBase(repoRoot, scope)
        cachedBase = scope to resolved
        return resolved
    }

    private fun computeBase(repoRoot: Path, scope: ScopeConfig): String? {
        val candidates = baseCandidates(repoRoot, scope)
        for ((ref, source) in candidates) {
            if (!refExists(repoRoot, ref)) continue
            val mergeBase = runner.run(repoRoot, listOf("git", "merge-base", "HEAD", ref)).trim()
            if (mergeBase.matches(COMMIT_SHA)) {
                val distance = commitDistance(repoRoot, mergeBase)
                val distanceNote = distance?.let { " ($it commit(s) ahead of base)" } ?: ""
                System.err.println(
                    "Reviewsmith: reviewing changes vs $ref @ ${mergeBase.take(8)} (base from $source)$distanceNote",
                )
                if (distance != null && distance > STALE_BASE_WARN_COMMITS) {
                    System.err.println(
                        "Reviewsmith: WARNING — the merge-base is $distance commit(s) behind HEAD. " +
                            "If that seems too large, the base branch may be stale; run with --dry-run to check scope.",
                    )
                }
                return mergeBase
            }
        }
        return null
    }

    /** Commits on HEAD since the merge-base; null if git can't answer (never blocks the run). */
    private fun commitDistance(repoRoot: Path, mergeBase: String): Int? =
        runCatching {
            runner.run(repoRoot, listOf("git", "rev-list", "--count", "$mergeBase..HEAD")).trim().toInt()
        }.getOrNull()

    /**
     * Ordered (ref, source) candidates; a branch name yields both `origin/<name>` and `<name>`,
     * remote-first, so a stale local branch never wins over the remote. An explicit config
     * baseRef that is already a SHA or `origin/`/`refs/`-qualified ref is used verbatim.
     */
    private fun baseCandidates(repoRoot: Path, scope: ScopeConfig): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        fun addBranch(name: String, source: String) {
            out += "origin/$name" to source
            out += name to source
        }

        scope.baseRef?.trim()?.takeIf { it.isNotEmpty() }?.let { ref ->
            if (ref.matches(COMMIT_SHA) || ref.startsWith("origin/") || ref.startsWith("refs/")) {
                out += ref to "config"
            } else {
                addBranch(ref, "config")
            }
        }

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
                .filter {
                    val p = it.toString()
                    !p.contains("/.git/") && !p.contains("/build/") && !p.contains("/.reviewsmith/")
                }
                .map { repoRoot.relativize(it).toString() }
                .toList()
        }
    }

}
