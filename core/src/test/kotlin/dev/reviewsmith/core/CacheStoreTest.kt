package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class CacheStoreTest {

    private val config = CacheConfig(enabled = true, dir = ".reviewsmith/cache", maxEntries = 500)

    private fun findings() = listOf(
        Finding(ruleId = "r", file = "A.kt", line = 1, severity = Severity.WARNING, message = "m"),
    )

    private fun cacheDir(repo: Path) = repo.resolve(".reviewsmith/cache")

    @Test
    fun `lookup on empty cache returns null`(@TempDir repo: Path) {
        val store = CacheStore.forConfig(config, repo)
        assertNull(store.lookup("abc"))
    }

    @Test
    fun `put then lookup round-trips findings`(@TempDir repo: Path) {
        val store = CacheStore.forConfig(config, repo)
        store.put("k1", "r", "A.kt", findings())
        val got = store.lookup("k1")
        assertEquals(1, got?.size)
        assertEquals("A.kt", got?.first()?.file)
    }

    @Test
    fun `corrupt entry is treated as a miss`(@TempDir repo: Path) {
        val store = CacheStore.forConfig(config, repo)
        Files.writeString(cacheDir(repo).resolve("bad.json"), "{ not valid json")
        assertNull(store.lookup("bad"))
    }

    @Test
    fun `wrong version is treated as a miss`(@TempDir repo: Path) {
        val store = CacheStore.forConfig(config, repo)
        val entry = """{"version":999,"key":"v","ruleId":"r","file":"A.kt","findings":[],"cachedAt":"x"}"""
        Files.writeString(cacheDir(repo).resolve("v.json"), entry)
        assertNull(store.lookup("v"))
    }

    @Test
    fun `first write creates a self-covering gitignore inside the cache dir`(@TempDir repo: Path) {
        CacheStore.forConfig(config, repo)
        val gitignore = cacheDir(repo).resolve(".gitignore")
        assertTrue(Files.exists(gitignore))
        assertEquals("*", Files.readString(gitignore).trim())
    }

    @Test
    fun `pruneIfNeeded evicts oldest beyond maxEntries`(@TempDir repo: Path) {
        val store = CacheStore.forConfig(CacheConfig(enabled = true, maxEntries = 2), repo)
        // cachedAt is written as Instant.now(); stagger by writing entries with explicit ages.
        writeEntry(repo, "old", "2020-01-01T00:00:00Z")
        writeEntry(repo, "mid", "2021-01-01T00:00:00Z")
        writeEntry(repo, "new", "2022-01-01T00:00:00Z")
        store.pruneIfNeeded()
        val remaining = Files.list(cacheDir(repo)).use { s ->
            s.filter { it.fileName.toString().endsWith(".json") }.map { it.fileName.toString() }.toList()
        }
        assertEquals(2, remaining.size)
        assertFalse(remaining.contains("old.json"), "oldest should be evicted")
        assertTrue(remaining.contains("new.json"))
    }

    @Test
    fun `pruneIfNeeded removes orphaned temp files`(@TempDir repo: Path) {
        val store = CacheStore.forConfig(config, repo)
        Files.writeString(cacheDir(repo).resolve("k.uuid.tmp"), "partial")
        store.pruneIfNeeded()
        assertFalse(Files.exists(cacheDir(repo).resolve("k.uuid.tmp")))
    }

    @Test
    fun `concurrent puts of distinct keys do not corrupt entries`(@TempDir repo: Path) {
        val store = CacheStore.forConfig(config, repo)
        runBlocking {
            (0 until 20).map { i ->
                async { store.put("key$i", "r", "A$i.kt", findings()) }
            }.awaitAll()
        }
        for (i in 0 until 20) {
            assertEquals(1, store.lookup("key$i")?.size, "key$i should round-trip cleanly")
        }
    }

    @Test
    fun `unwritable cache dir degrades to a no-op store`(@TempDir repo: Path) {
        val fileWhereDirShouldBe = repo.resolve("blocked")
        Files.writeString(fileWhereDirShouldBe, "x")
        val store = CacheStore.forConfig(CacheConfig(enabled = true, dir = "blocked/cache"), repo)
        store.put("k", "r", "A.kt", findings())
        assertNull(store.lookup("k"))
    }

    @Test
    fun `noOp store never reads or writes`(@TempDir repo: Path) {
        val store = CacheStore.noOp()
        store.put("k", "r", "A.kt", findings())
        assertNull(store.lookup("k"))
        assertFalse(Files.exists(cacheDir(repo)))
    }

    @Test
    fun `refreshMode writes but never reads`(@TempDir repo: Path) {
        val store = CacheStore.refreshMode(config, repo)
        store.put("k", "r", "A.kt", findings())
        assertNull(store.lookup("k"), "refresh mode always misses on read")
        assertTrue(Files.exists(cacheDir(repo).resolve("k.json")), "but the entry is written")
    }

    private fun writeEntry(repo: Path, key: String, cachedAt: String) {
        val entry = """{"version":1,"key":"$key","ruleId":"r","file":"A.kt","findings":[],"cachedAt":"$cachedAt"}"""
        Files.writeString(cacheDir(repo).resolve("$key.json"), entry)
    }
}
