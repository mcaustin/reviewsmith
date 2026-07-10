package dev.reviewsmith.core

import java.nio.file.Files
import java.nio.file.Path

/**
 * Derives the content-addressed cache key for one (rule × file) work unit. The key covers
 * every declared input that changes the agent's answer: rule identity + body, the target
 * file's content, the contents of every doc the agent may read, the model, the tool list,
 * the engine's prompt + output schema, and the budget cap. `callTimeoutSeconds` is excluded
 * — a timeout throws before a write, so it can never poison an entry.
 */
object CacheKeyBuilder {
    fun build(
        rule: Rule,
        filePath: String,
        docPaths: List<String>,
        repoRoot: Path,
        effectiveModel: String,
        allowedTools: String,
    ): String {
        val allDocPaths = (docPaths + rule.docs).distinct().sorted()
        val docHash = sha256hex(
            allDocPaths.joinToString("|") { path ->
                runCatching { sha256hex(Files.readAllBytes(repoRoot.resolve(path))) }
                    .getOrDefault(sha256hex(ByteArray(0)))
            }.toByteArray(Charsets.UTF_8),
        )
        val promptHash = sha256hex(
            (Prompts.ruleSystemPrompt() + Prompts.findingsSchema).toByteArray(Charsets.UTF_8),
        )
        val fileHash = runCatching { sha256hex(Files.readAllBytes(repoRoot.resolve(filePath))) }
            .getOrDefault(sha256hex(ByteArray(0)))
        val components = listOf(
            rule.id,
            rule.name,
            sha256hex(rule.body.toByteArray(Charsets.UTF_8)),
            fileHash,
            docHash,
            effectiveModel,
            allowedTools,
            promptHash,
            rule.maxBudgetUsd?.toString() ?: "none",
        )
        return sha256hex(components.joinToString("|").toByteArray(Charsets.UTF_8))
    }
}
