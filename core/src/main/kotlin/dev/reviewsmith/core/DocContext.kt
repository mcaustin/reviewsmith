package dev.reviewsmith.core

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile

object DocContextBuilder {
    private val wellKnown = listOf("CLAUDE.md", "README.md", "ARCHITECTURE.md")
    private val docDirs = listOf("docs", "adr")

    fun discover(repoRoot: Path, config: DocsConfig): List<String> {
        if (!config.autoDiscover) return emptyList()
        val found = mutableListOf<String>()

        for (name in wellKnown) {
            val p = repoRoot.resolve(name)
            if (p.isRegularFile()) found += name
        }

        for (dir in docDirs) {
            val d = repoRoot.resolve(dir)
            if (Files.isDirectory(d)) {
                Files.walk(d).use { stream ->
                    stream.filter { it.isRegularFile() }
                        .filter { it.toString().endsWith(".md") }
                        .limit(config.maxDocs.toLong())
                        .forEach { found += repoRoot.relativize(it).toString() }
                }
            }
        }

        return found.distinct().take(config.maxDocs)
    }
}
