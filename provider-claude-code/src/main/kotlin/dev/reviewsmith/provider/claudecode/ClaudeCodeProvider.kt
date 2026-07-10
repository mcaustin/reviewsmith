package dev.reviewsmith.provider.claudecode

import dev.reviewsmith.spi.AgentProvider
import dev.reviewsmith.spi.AgentRequest
import dev.reviewsmith.spi.AgentResult
import dev.reviewsmith.spi.Confidence
import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import dev.reviewsmith.spi.TokenUsage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import java.util.Locale

/**
 * An [AgentProvider] that shells out to the local `claude` CLI in headless mode with a
 * structured output schema. Read-only tools only.
 */
class ClaudeCodeProvider(
    private val model: String? = null,
    private val claudeBin: String = "claude",
    private val runner: ProcessRunner = DefaultProcessRunner,
) : AgentProvider {
    override val id: String = "claude-code"
    override val effectiveModel: String? get() = model
    override val allowedTools: String = ALLOWED_TOOLS

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    override fun analyze(request: AgentRequest): AgentResult {
        val command = buildList {
            add(claudeBin)
            add("-p")
            add("--output-format"); add("json")
            add("--json-schema"); add(request.outputSchema)
            add("--append-system-prompt"); add(request.systemPrompt)
            add("--allowedTools"); add(ALLOWED_TOOLS)
            add("--add-dir"); add(request.projectRoot)
            if (model != null) { add("--model"); add(model) }
            if (request.maxBudgetUsd != null) {
                add("--max-budget-usd"); add("%.6f".format(Locale.ROOT, request.maxBudgetUsd))
            }
        }

        // The prompt is passed on stdin; passing it positionally alongside an inline
        // --json-schema value confuses the CLI's argument parsing.
        val budgetInEffect = request.maxBudgetUsd != null
        var output = runner.run(
            request.projectRoot,
            command,
            request.rulePrompt,
            timeoutSeconds = request.callTimeoutSeconds,
            budgetInEffect = budgetInEffect,
        )
        var findings = parse(output)
        // Retry once on empty/unparseable output. A timeout or budget-cap throws out of
        // runner.run instead, so those never reach this retry.
        if (findings == null) {
            output = runner.run(
                request.projectRoot,
                command,
                request.rulePrompt,
                timeoutSeconds = request.callTimeoutSeconds,
                budgetInEffect = budgetInEffect,
            )
            findings = parse(output)
        }
        val telemetry = extractTelemetry(output)
        return AgentResult(
            findings = findings ?: emptyList(),
            modelId = model,
            rawOutput = output,
            usage = telemetry.usage,
            durationMs = telemetry.durationMs,
            costUsd = telemetry.costUsd,
        )
    }

    private data class Telemetry(
        val durationMs: Long? = null,
        val costUsd: Double? = null,
        val usage: TokenUsage? = null,
    )

    /** Pulls timing/cost/usage from the top-level `--output-format json` envelope. */
    private fun extractTelemetry(output: String): Telemetry {
        if (output.isBlank()) return Telemetry()
        val envelope = runCatching { json.parseToJsonElement(output) }.getOrNull() as? JsonObject
            ?: return Telemetry()
        fun num(key: String) = envelope[key]?.jsonPrimitiveOrNull()?.contentOrNull
        val usageObj = envelope["usage"] as? JsonObject
        val usage = usageObj?.let {
            fun u(key: String) = it[key]?.jsonPrimitiveOrNull()?.contentOrNull?.toLongOrNull()
            TokenUsage(
                inputTokens = u("input_tokens"),
                outputTokens = u("output_tokens"),
                cacheReadInputTokens = u("cache_read_input_tokens"),
            )
        }
        return Telemetry(
            durationMs = num("duration_ms")?.toLongOrNull(),
            costUsd = num("total_cost_usd")?.toDoubleOrNull(),
            usage = usage,
        )
    }

    /** Returns null when the output could not be parsed at all (triggers a retry). */
    private fun parse(output: String): List<Finding>? {
        if (output.isBlank()) return null
        val root = runCatching { json.parseToJsonElement(output) }.getOrNull() ?: return null

        // `--output-format json` wraps the model output in an envelope with a `result`
        // field. The `result` may itself be a JSON object/array (per --json-schema) or a
        // JSON string that must be parsed again.
        val payload = extractPayload(root) ?: return null
        val findingsArray = findFindingsArray(payload) ?: return null
        return findingsArray.mapNotNull { toFinding(it) }
    }

    private fun extractPayload(root: JsonElement): JsonElement? {
        if (root is JsonObject && root.containsKey("result")) {
            val result = root["result"]!!
            val asString = result.jsonPrimitiveOrNull()?.contentOrNull
            if (asString != null) {
                return runCatching { json.parseToJsonElement(asString) }.getOrNull()
            }
            return result
        }
        return root
    }

    private fun findFindingsArray(payload: JsonElement): JsonArray? {
        return when (payload) {
            is JsonArray -> payload
            is JsonObject -> (payload["findings"] as? JsonArray)
            else -> null
        }
    }

    private fun toFinding(element: JsonElement): Finding? {
        val obj = element as? JsonObject ?: return null
        val file = obj["file"]?.jsonPrimitiveOrNull()?.contentOrNull ?: return null
        val message = obj["message"]?.jsonPrimitiveOrNull()?.contentOrNull ?: return null
        val line = obj["line"]?.jsonPrimitiveOrNull()?.intOrNull
        val severity = parseSeverity(obj["severity"]?.jsonPrimitiveOrNull()?.contentOrNull)
        val rationale = obj["rationale"]?.jsonPrimitiveOrNull()?.contentOrNull
        val confidence = obj["confidence"]?.jsonPrimitiveOrNull()?.contentOrNull
            ?.let { runCatching { Confidence.valueOf(it.uppercase()) }.getOrNull() }
        val ruleId = obj["ruleId"]?.jsonPrimitiveOrNull()?.contentOrNull ?: ""
        return Finding(
            ruleId = ruleId,
            file = file,
            line = line,
            severity = severity,
            message = message,
            rationale = rationale,
            confidence = confidence,
        )
    }

    private fun parseSeverity(raw: String?): Severity = when (raw?.lowercase()) {
        "error", "high", "critical", "blocker" -> Severity.ERROR
        "warning", "medium", "warn", "major" -> Severity.WARNING
        "info", "low", "minor", "nit" -> Severity.INFO
        else -> Severity.WARNING
    }

    private fun JsonElement.jsonPrimitiveOrNull() = (this as? JsonPrimitive)

    private companion object {
        const val ALLOWED_TOOLS = "Read,Grep,Glob"
    }
}
