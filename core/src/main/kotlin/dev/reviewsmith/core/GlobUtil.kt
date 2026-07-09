package dev.reviewsmith.core

import java.nio.file.FileSystems
import java.nio.file.Path

object GlobUtil {
    fun matcher(glob: String): (String) -> Boolean {
        val fs = FileSystems.getDefault()
        val matchers = buildList {
            add(fs.getPathMatcher("glob:$glob"))
            // `**/*.kt` misses top-level files; also match the tail pattern directly.
            if (glob.startsWith("**/")) add(fs.getPathMatcher("glob:${glob.removePrefix("**/")}"))
        }
        return { rel ->
            val p = Path.of(rel)
            matchers.any { it.matches(p) }
        }
    }
}
