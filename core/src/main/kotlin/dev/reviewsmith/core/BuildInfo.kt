package dev.reviewsmith.core

object BuildInfo {
    val version: String = runCatching {
        BuildInfo::class.java.getResourceAsStream("/reviewsmith-version.properties")?.use { stream ->
            java.util.Properties().apply { load(stream) }.getProperty("version")
        }
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: "0.0.0+dev"
}
