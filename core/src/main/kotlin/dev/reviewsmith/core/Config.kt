package dev.reviewsmith.core

import com.charleskorn.kaml.Yaml
import dev.reviewsmith.spi.Confidence
import kotlinx.serialization.Serializable
import java.nio.file.Files
import java.nio.file.Path

@Serializable
data class ScopeConfig(
    val default: String = "changed",
    val baseRef: String? = null,
    val detectBase: Boolean = true,
    val include: List<String> = listOf("**/*.kt", "**/*.java", "**/*.ts", "**/*.tsx"),
    val includeDiff: Boolean = true,
)

@Serializable
data class DocsConfig(
    val autoDiscover: Boolean = true,
    val maxDocs: Int = 8,
)

@Serializable
data class ValidatorConfig(
    val enabled: Boolean = true,
    val timeoutSeconds: Long = 600,
    val chunkSize: Int = 20,
)

@Serializable
data class RuleOverride(
    val enabled: Boolean? = null,
    val severity: String? = null,
    val maxBudgetUsd: Double? = null,
    val callTimeoutSeconds: Long? = null,
)

@Serializable
data class BaselineConfig(
    val enabled: Boolean = true,
    val path: String = "reviewsmith-baseline.json",
)

@Serializable
data class SuppressionConfig(
    val enabled: Boolean = true,
    val band: Int = 2,
)

@Serializable
data class CacheConfig(
    val enabled: Boolean = false,
    val dir: String = ".reviewsmith/cache",
    val maxEntries: Int = 500,
)

@Serializable
data class AgentConfig(
    val isolation: String = "strict",
) {
    fun hermetic(): Boolean = isolation.lowercase() != "local"
}

enum class FailOnLevel { NONE, WARNING, ERROR }

@Serializable
data class GateConfig(
    val failOn: String = "none",
    val failOnCategory: List<String> = emptyList(),
    val onlyConfidence: String = "clear",
) {
    fun failOnLevel(): FailOnLevel = when (failOn.uppercase()) {
        "WARNING" -> FailOnLevel.WARNING
        "ERROR" -> FailOnLevel.ERROR
        else -> FailOnLevel.NONE
    }

    /** Confidence levels that are allowed to gate. `clear` (default) = CLEAR only; `ambiguous`/`all` also gate AMBIGUOUS. */
    fun gatedConfidences(): Set<Confidence> = when (onlyConfidence.lowercase()) {
        "ambiguous", "all" -> setOf(Confidence.CLEAR, Confidence.AMBIGUOUS)
        else -> setOf(Confidence.CLEAR)
    }
}

@Serializable
data class ReviewsmithConfig(
    val scope: ScopeConfig = ScopeConfig(),
    val docs: DocsConfig = DocsConfig(),
    val validator: ValidatorConfig = ValidatorConfig(),
    val maxConcurrency: Int = 6,
    val callTimeoutSeconds: Long = 300,
    val buildUponDefault: Boolean = true,
    val ruleSources: List<String>? = null,
    val onlyRules: List<String>? = null,
    val rules: Map<String, RuleOverride> = emptyMap(),
    val baseline: BaselineConfig = BaselineConfig(),
    val suppression: SuppressionConfig = SuppressionConfig(),
    val cache: CacheConfig = CacheConfig(),
    val gate: GateConfig = GateConfig(),
    val agent: AgentConfig = AgentConfig(),
) {
    /** The rule sources to read, honoring an explicit list or the built-in default order. */
    fun effectiveRuleSources(): List<String> =
        ruleSources ?: if (buildUponDefault) {
            listOf(SOURCE_SHIPPED, ".claude/rules", "reviewsmith/rules")
        } else {
            listOf(".claude/rules", "reviewsmith/rules")
        }

    companion object {
        const val SOURCE_SHIPPED = "shipped"

        fun load(repoRoot: Path): ReviewsmithConfig {
            val file = repoRoot.resolve("reviewsmith.yml")
            if (!Files.exists(file)) return ReviewsmithConfig()
            val text = Files.readString(file)
            return parse(text)
        }

        fun parse(text: String): ReviewsmithConfig {
            if (text.isBlank()) return ReviewsmithConfig()
            return Yaml.default.decodeFromString(serializer(), text)
        }
    }
}
