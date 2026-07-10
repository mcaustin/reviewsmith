package dev.reviewsmith.core

import kotlinx.serialization.json.Json

internal val reviewsmithJson = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
}
