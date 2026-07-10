package dev.reviewsmith.core

import kotlinx.serialization.json.Json

internal val reviewsmithJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}

internal val sarifJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    explicitNulls = false
}

internal val reportJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}
