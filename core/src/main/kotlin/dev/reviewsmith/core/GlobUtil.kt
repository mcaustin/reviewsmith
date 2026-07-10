package dev.reviewsmith.core

import java.nio.file.FileSystems
import java.nio.file.Path

// Glob matching with gitignore/ripgrep-style doublestar semantics: a doublestar matches
// zero OR more path segments. Java's PathMatcher requires at least one middle segment for
// "a/<doublestar>/b" (so it misses "a/b"), which surprises everyone used to gitignore. To
// restore the expected behavior without a new dependency, each doublestar-slash occurrence
// is expanded into both its zero-segment and one-or-more forms and the resulting variant
// globs are matched as a set.
object GlobUtil {
    fun matcher(glob: String): (String) -> Boolean {
        val fs = FileSystems.getDefault()
        val matchers = expand(glob).map { fs.getPathMatcher("glob:$it") }
        return { rel ->
            val p = Path.of(rel)
            matchers.any { it.matches(p) }
        }
    }

    // Returns the glob plus zero-segment variants for each doublestar-slash occurrence.
    // "a/**/b" -> ["a/**/b", "a/b"]; a glob with two such occurrences yields all four
    // combinations, so a doublestar matches zero or more segments.
    private fun expand(glob: String): Set<String> {
        val results = linkedSetOf(glob)
        // Each `**/` can also mean "nothing here". Repeatedly drop one `**/` occurrence
        // from every known variant until no new variants appear.
        var frontier = listOf(glob)
        while (frontier.isNotEmpty()) {
            val next = mutableListOf<String>()
            for (g in frontier) {
                var from = 0
                while (true) {
                    val at = g.indexOf("**/", from)
                    if (at < 0) break
                    val variant = g.substring(0, at) + g.substring(at + 3)
                    if (results.add(variant)) next.add(variant)
                    from = at + 3
                }
            }
            frontier = next
        }
        return results
    }
}
