package dev.reviewsmith.core

import kotlinx.serialization.Serializable

@Serializable
data class BaselineEntry(
    val fingerprint: String,
    val ruleId: String,
    val normalizedFile: String,
    val count: Int,
)

@Serializable
data class BaselineFile(
    val version: Int = VERSION,
    val createdAt: String = "",
    val entries: List<BaselineEntry> = emptyList(),
) {
    companion object {
        const val VERSION = 1
    }
}
