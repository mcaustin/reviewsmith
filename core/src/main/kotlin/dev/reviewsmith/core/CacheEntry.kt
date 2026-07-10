package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import kotlinx.serialization.Serializable

@Serializable
data class CacheEntry(
    val version: Int = VERSION,
    val key: String,
    val ruleId: String,
    val file: String,
    val findings: List<Finding>,
    val cachedAt: String,
) {
    companion object {
        const val VERSION = 1
    }
}
