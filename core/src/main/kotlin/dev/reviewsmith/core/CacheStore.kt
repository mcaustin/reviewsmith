package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.UUID
import kotlin.io.path.name

/**
 * A content-addressed, per-(rule × file) response cache on the filesystem. Tolerant of a
 * missing, unwritable, or corrupt cache like [BaselineStore]: any failure degrades to a
 * cache miss / skipped write, never a crash or a wrong result.
 */
class CacheStore private constructor(
    private val cacheDir: Path?,
    private val maxEntries: Int,
    private val readBypass: Boolean,
) {
    /** Returns cached findings for [key], or null on a miss / any read failure. */
    fun lookup(key: String): List<Finding>? {
        if (cacheDir == null || readBypass) return null
        val file = cacheDir.resolve("$key.json")
        val entry = runCatching {
            reviewsmithJson.decodeFromString(CacheEntry.serializer(), Files.readString(file))
        }.getOrNull() ?: return null
        if (entry.version != CacheEntry.VERSION) return null
        return entry.findings
    }

    /** Writes findings for [key] atomically. A failure is logged once and swallowed. */
    fun put(key: String, ruleId: String, file: String, findings: List<Finding>) {
        val dir = cacheDir ?: return
        val entry = CacheEntry(
            key = key,
            ruleId = ruleId,
            file = file,
            findings = findings,
            cachedAt = Instant.now().toString(),
        )
        runCatching {
            val text = reviewsmithJson.encodeToString(CacheEntry.serializer(), entry)
            val tmp = dir.resolve("$key.${UUID.randomUUID()}.tmp")
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, dir.resolve("$key.json"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: AtomicMoveNotSupportedException) {
                Files.copy(tmp, dir.resolve("$key.json"), StandardCopyOption.REPLACE_EXISTING)
                Files.deleteIfExists(tmp)
            }
        }.onFailure {
            System.err.println("Reviewsmith: cache write failed for $ruleId/$file: ${it.message}")
        }
    }

    /** Removes orphaned temp files, then evicts oldest entries down to [maxEntries]. */
    fun pruneIfNeeded() {
        val dir = cacheDir ?: return
        runCatching {
            Files.list(dir).use { stream ->
                stream.filter { it.name.endsWith(".tmp") }.forEach { Files.deleteIfExists(it) }
            }
            val entries = Files.list(dir).use { stream ->
                stream.filter { it.name.endsWith(".json") }.toList()
            }
            if (entries.size <= maxEntries) return
            val byAge = entries.mapNotNull { path ->
                val cachedAt = runCatching {
                    reviewsmithJson.decodeFromString(CacheEntry.serializer(), Files.readString(path)).cachedAt
                }.getOrDefault("")
                path to cachedAt
            }.sortedBy { it.second }
            val toDelete = byAge.take(entries.size - maxEntries)
            toDelete.forEach { Files.deleteIfExists(it.first) }
            System.err.println("Reviewsmith: cache pruned ${toDelete.size} entries")
        }
    }

    companion object {
        /** A disabled store: never reads or writes. */
        fun noOp(): CacheStore = CacheStore(cacheDir = null, maxEntries = 0, readBypass = true)

        /**
         * Resolves the cache dir from config, creating it and verifying it is writable.
         * Returns a no-op store if the directory cannot be prepared.
         */
        fun forConfig(config: CacheConfig, repoRoot: Path): CacheStore =
            prepare(config, repoRoot, readBypass = false)

        /** Like [forConfig] but every lookup misses, so results are always re-written. */
        fun refreshMode(config: CacheConfig, repoRoot: Path): CacheStore =
            prepare(config, repoRoot, readBypass = true)

        private fun prepare(config: CacheConfig, repoRoot: Path, readBypass: Boolean): CacheStore {
            val dir = repoRoot.resolve(config.dir)
            val ready = runCatching {
                Files.createDirectories(dir)
                val probe = dir.resolve(".probe-${UUID.randomUUID()}")
                Files.writeString(probe, "")
                Files.deleteIfExists(probe)
                writeGitignore(dir)
                true
            }.getOrElse {
                System.err.println("Reviewsmith: cache dir $dir is unusable (${it.message}); running without cache.")
                false
            }
            return if (ready) {
                CacheStore(dir, config.maxEntries, readBypass)
            } else {
                noOp()
            }
        }

        private fun writeGitignore(dir: Path) {
            val gitignore = dir.resolve(".gitignore")
            if (!Files.exists(gitignore)) {
                runCatching { Files.writeString(gitignore, "*\n") }
            }
        }
    }
}
