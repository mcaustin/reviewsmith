package dev.reviewsmith.core

import java.nio.file.Files
import java.nio.file.Path

/** In-memory baseline: fingerprint -> accepted occurrence count. */
class BaselineStore private constructor(
    private val counts: Map<String, Int>,
) {
    fun countFor(fingerprint: String): Int = counts[fingerprint] ?: 0

    companion object {
        fun empty(): BaselineStore = BaselineStore(emptyMap())

        fun resolveFromConfig(config: ReviewsmithConfig, repoRoot: Path): BaselineStore {
            val path = repoRoot.resolve(config.baseline.path)
            return load(path)
        }

        fun load(path: Path): BaselineStore {
            if (!Files.exists(path)) return empty()
            val parsed = runCatching {
                reviewsmithJson.decodeFromString(BaselineFile.serializer(), Files.readString(path))
            }.getOrElse {
                System.err.println("Reviewsmith: baseline at $path is unreadable; ignoring it.")
                return empty()
            }
            if (parsed.version != BaselineFile.VERSION) {
                System.err.println(
                    "Reviewsmith: baseline version ${parsed.version} not supported " +
                        "(expected ${BaselineFile.VERSION}); ignoring baseline.",
                )
                return empty()
            }
            return BaselineStore(parsed.entries.associate { it.fingerprint to it.count })
        }
    }
}
