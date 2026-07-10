package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import java.security.MessageDigest

/**
 * A location-tolerant finding fingerprint keyed only on `(ruleId, normalizedFile)`. Line
 * numbers and message wording are LLM-assigned and drift between runs, so they are
 * excluded; a `(ruleId, file)` pair is treated as a count bucket instead.
 */
object Fingerprint {
    fun normalizeFile(file: String): String {
        val slashed = file.replace('\\', '/')
        val noDrive = slashed.replace(Regex("^[A-Za-z]:/"), "")
        return noDrive.trimStart('/')
    }

    fun of(finding: Finding): String = computeKey(finding.ruleId, normalizeFile(finding.file))

    fun computeKey(ruleId: String, normalizedFile: String): String =
        sha256hex("$ruleId|$normalizedFile".toByteArray(Charsets.UTF_8)).take(12)
}

/** Full 64-hex-char SHA-256 of [bytes]. */
internal fun sha256hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(bytes).joinToString("") { "%02x".format(it) }
}
