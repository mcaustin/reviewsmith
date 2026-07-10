package dev.reviewsmith.core

import dev.reviewsmith.spi.Severity
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Resolves the effective rule set from the configured, ordered list of sources.
 *
 * Sources are read in order; a rule from a later source replaces an earlier rule with the
 * same id (detekt-style layering). Per-rule overrides from config (`enabled`, `severity`)
 * are applied last: disabled rules are dropped, severity overrides re-tune the rest.
 */
object RuleResolver {
    private val bundled = listOf(
        "correctness-safety",
        "simplification",
        "style-convention",
        "design-impact",
        "evolution-safety",
        "secrets-in-code",
        "pii-logging",
        "backward-compatible-migrations",
    )

    fun resolve(repoRoot: Path, config: ReviewsmithConfig): List<Rule> {
        val byId = LinkedHashMap<String, Rule>()
        for (source in config.effectiveRuleSources()) {
            for (rule in readSource(repoRoot, source)) {
                byId[rule.id] = rule
            }
        }
        return byId.values
            .mapNotNull { applyOverride(it, config.rules[it.id]) }
            .toList()
    }

    private fun readSource(repoRoot: Path, source: String): List<Rule> =
        if (source == ReviewsmithConfig.SOURCE_SHIPPED) loadShipped()
        else loadDirectory(repoRoot.resolve(source))

    private fun loadShipped(): List<Rule> = bundled.map { id ->
        val resource = "/reviewsmith/rules/$id.md"
        val text = RuleResolver::class.java.getResourceAsStream(resource)
            ?.bufferedReader()?.readText()
            ?: error("bundled rule not found: $resource")
        RuleParser.parse(id, text)
    }

    private fun loadDirectory(dir: Path): List<Rule> {
        if (!dir.isDirectory()) return emptyList()
        Files.list(dir).use { stream ->
            return stream
                .filter { it.isRegularFile() && it.name.endsWith(".md") }
                .sorted()
                .map { path -> RuleParser.parse(path.name.removeSuffix(".md"), path.readText()) }
                .toList()
        }
    }

    /** Returns the (possibly re-tuned) rule, or null if the override disables it. */
    private fun applyOverride(rule: Rule, override: RuleOverride?): Rule? {
        if (override == null) return rule
        if (override.enabled == false) return null
        val severity = override.severity?.let { raw ->
            runCatching { Severity.valueOf(raw.uppercase()) }.getOrElse {
                System.err.println(
                    "Reviewsmith: unknown severity \"$raw\" for rule ${rule.id} — using ${rule.severity}.",
                )
                rule.severity
            }
        } ?: rule.severity
        return rule.copy(
            severity = severity,
            maxBudgetUsd = override.maxBudgetUsd ?: rule.maxBudgetUsd,
        )
    }
}
