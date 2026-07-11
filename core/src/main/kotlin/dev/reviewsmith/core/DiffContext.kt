package dev.reviewsmith.core

/**
 * Per-file unified diffs for the changed scope, keyed by repo-relative path. Assembled by
 * [ScopeResolver.captureDiffs] and threaded into the rule and validator prompts so the agent
 * sees the actual changed lines, not just the filename. A prompt-assembly concern — kept in
 * core, out of the provider SPI.
 */
data class DiffContext(val byFile: Map<String, String>) {
    fun forFile(file: String): String? = byFile[file]?.takeIf { it.isNotBlank() }

    /** The diffs for [files] concatenated (each git diff carries its own file header). */
    fun forFiles(files: List<String>): String =
        files.distinct().mapNotNull { forFile(it) }.joinToString("\n\n")

    companion object {
        val EMPTY = DiffContext(emptyMap())

        /** Upper bound on a single file's diff text, so a huge change can't blow up the prompt or cache key. */
        const val MAX_DIFF_CHARS = 16_000
    }
}
