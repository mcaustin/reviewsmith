package dev.reviewsmith.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class DocContextTest {

    @Test
    fun `autoDiscover false yields no docs`(@TempDir repo: Path) {
        Files.writeString(repo.resolve("README.md"), "x")
        assertTrue(DocContextBuilder.discover(repo, DocsConfig(autoDiscover = false)).isEmpty())
    }

    @Test
    fun `total docs capped at maxDocs across directories`(@TempDir repo: Path) {
        val docs = repo.resolve("docs")
        Files.createDirectories(docs)
        for (i in 1..10) Files.writeString(docs.resolve("d$i.md"), "x")
        val found = DocContextBuilder.discover(repo, DocsConfig(maxDocs = 3))
        assertEquals(3, found.size, "output must be capped at maxDocs: $found")
    }

    @Test
    fun `well-known files are discovered`(@TempDir repo: Path) {
        Files.writeString(repo.resolve("CLAUDE.md"), "x")
        val found = DocContextBuilder.discover(repo, DocsConfig(maxDocs = 8))
        assertTrue(found.contains("CLAUDE.md"), "well-known doc must be found: $found")
    }
}
