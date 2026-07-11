package dev.reviewsmith.core

import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CacheKeyBuilderTest {

    private fun rule(
        id: String = "correctness",
        name: String = "Correctness",
        body: String = "check for bugs",
        docs: List<String> = emptyList(),
        maxBudgetUsd: Double? = null,
    ) = Rule(id, name, Severity.WARNING, listOf("**/*.kt"), docs, emptyList(), body, maxBudgetUsd)

    private fun seedFile(repo: Path, rel: String, content: String): String {
        val p = repo.resolve(rel)
        Files.createDirectories(p.parent)
        Files.writeString(p, content)
        return rel
    }

    private fun key(
        repo: Path,
        rule: Rule = rule(),
        file: String = "A.kt",
        docPaths: List<String> = emptyList(),
        model: String = "opus",
        tools: String = "Read,Grep,Glob",
        diff: String = "",
    ) = CacheKeyBuilder.build(rule, file, docPaths, repo, model, tools, diff)

    @Test
    fun `same inputs produce the same key`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        assertEquals(key(repo), key(repo))
    }

    @Test
    fun `key is 64 hex chars`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        val k = key(repo)
        assertEquals(64, k.length)
        assert(k.all { it in "0123456789abcdef" })
    }

    @Test
    fun `different file content changes the key`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        val before = key(repo)
        seedFile(repo, "A.kt", "class A { fun x() {} }")
        assertNotEquals(before, key(repo))
    }

    @Test
    fun `different rule body changes the key`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        assertNotEquals(key(repo, rule = rule(body = "one")), key(repo, rule = rule(body = "two")))
    }

    @Test
    fun `different rule name changes the key`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        assertNotEquals(key(repo, rule = rule(name = "N1")), key(repo, rule = rule(name = "N2")))
    }

    @Test
    fun `different global doc content changes the key`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        seedFile(repo, "CLAUDE.md", "rules v1")
        val before = key(repo, docPaths = listOf("CLAUDE.md"))
        seedFile(repo, "CLAUDE.md", "rules v2")
        assertNotEquals(before, key(repo, docPaths = listOf("CLAUDE.md")))
    }

    @Test
    fun `different per-rule doc content changes the key`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        seedFile(repo, "guide.md", "guide v1")
        val r = rule(docs = listOf("guide.md"))
        val before = key(repo, rule = r)
        seedFile(repo, "guide.md", "guide v2")
        assertNotEquals(before, key(repo, rule = r))
    }

    @Test
    fun `different model changes the key`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        assertNotEquals(key(repo, model = "opus"), key(repo, model = "sonnet"))
    }

    @Test
    fun `different allowedTools changes the key`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        assertNotEquals(key(repo, tools = "Read,Grep,Glob"), key(repo, tools = "Read"))
    }

    @Test
    fun `different maxBudgetUsd changes the key`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        val none = key(repo, rule = rule(maxBudgetUsd = null))
        val cheap = key(repo, rule = rule(maxBudgetUsd = 0.05))
        val rich = key(repo, rule = rule(maxBudgetUsd = 0.10))
        assertNotEquals(none, cheap)
        assertNotEquals(cheap, rich)
    }

    @Test
    fun `different diff changes the key`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        assertNotEquals(key(repo, diff = "@@ -1 +1 @@\n-a\n+b"), key(repo, diff = "@@ -1 +1 @@\n-a\n+c"))
    }

    @Test
    fun `adding a diff changes the key`(@TempDir repo: Path) {
        seedFile(repo, "A.kt", "class A")
        assertNotEquals(key(repo, diff = ""), key(repo, diff = "@@ -1 +1 @@\n-a\n+b"))
    }

    @Test
    fun `missing file produces a deterministic key without crashing`(@TempDir repo: Path) {
        assertEquals(key(repo, file = "ghost.kt"), key(repo, file = "ghost.kt"))
    }
}
