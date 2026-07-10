package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

object BaselineWriter {
    /** Writes [findings] as a baseline at [path], returning the number of distinct entries. */
    fun write(findings: List<Finding>, path: Path, now: String): Int {
        val entries = findings
            .groupBy { Fingerprint.of(it) }
            .map { (fp, group) ->
                val first = group.first()
                BaselineEntry(
                    fingerprint = fp,
                    ruleId = first.ruleId,
                    normalizedFile = Fingerprint.normalizeFile(first.file),
                    count = group.size,
                )
            }
            .sortedWith(compareBy({ it.ruleId }, { it.normalizedFile }))

        val file = BaselineFile(version = BaselineFile.VERSION, createdAt = now, entries = entries)
        val json = reviewsmithJson.encodeToString(BaselineFile.serializer(), file)

        val tmp = path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(tmp, json)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
        return entries.size
    }
}
