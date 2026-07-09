package dev.reviewsmith.core

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ScopeConfig(
    val default: String = "changed",
    val baseRef: String = "origin/main",
    val include: List<String> = listOf("**/*.kt", "**/*.java", "**/*.ts", "**/*.tsx"),
)

@Serializable
data class DocsConfig(
    val autoDiscover: Boolean = true,
    val maxDocs: Int = 8,
)

@Serializable
data class ValidatorConfig(
    val enabled: Boolean = true,
)

@Serializable
data class ReviewsmithConfig(
    val scope: ScopeConfig = ScopeConfig(),
    val docs: DocsConfig = DocsConfig(),
    val validator: ValidatorConfig = ValidatorConfig(),
    val maxConcurrency: Int = 4,
) {
    companion object {
        fun load(repoRoot: Path): ReviewsmithConfig {
            val file = repoRoot.resolve("reviewsmith.yml")
            if (!Files.exists(file)) return ReviewsmithConfig()
            val text = Files.readString(file)
            return Yaml.default.decodeFromString(serializer(), text)
        }
    }
}
