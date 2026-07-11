package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class JsonReporterTest {

    private val clock = Clock.fixed(Instant.parse("2025-10-14T12:00:00Z"), ZoneOffset.UTC)

    private fun report(): String {
        val result = RunResult(
            findings = listOf(Finding("r", "A.kt", 5, Severity.ERROR, "boom")),
            filesReviewed = 2,
            rulesRun = 3,
            totalCostUsd = 0.42,
            modelId = "opus",
        )
        return JsonReporter(ReviewsmithConfig(), clock).report(result)
    }

    private fun parse() = Json.parseToJsonElement(report()).jsonObject

    @Test
    fun `report has stable envelope fields`() {
        val root = parse()
        assertEquals("1", root["schemaVersion"]!!.jsonPrimitive.content)
        assertEquals("2025-10-14T12:00:00Z", root["generatedAt"]!!.jsonPrimitive.content)
        assertTrue(root.containsKey("runMeta"))
        assertTrue(root.containsKey("config"))
        assertTrue(root.containsKey("findings"))
    }

    @Test
    fun `runMeta carries cost and model`() {
        val meta = parse()["runMeta"]!!.jsonObject
        assertEquals(2, meta["filesReviewed"]!!.jsonPrimitive.content.toInt())
        assertEquals("opus", meta["modelId"]!!.jsonPrimitive.content)
        assertEquals(0.42, meta["totalCostUsd"]!!.jsonPrimitive.content.toDouble())
    }

    @Test
    fun `findings are embedded`() {
        val findings = parse()["findings"]!!.jsonArray
        assertEquals(1, findings.size)
        assertEquals("A.kt", findings[0].jsonObject["file"]!!.jsonPrimitive.content)
    }

    @Test
    fun `suggestedFix round-trips when present and is omitted when null`() {
        val result = RunResult(
            findings = listOf(
                Finding("r", "A.kt", 5, Severity.ERROR, "boom", suggestedFix = "use coerceAtMost(cap)"),
                Finding("r", "B.kt", 6, Severity.WARNING, "meh"),
            ),
            filesReviewed = 2,
            rulesRun = 1,
        )
        val findings = Json.parseToJsonElement(JsonReporter(ReviewsmithConfig(), clock).report(result))
            .jsonObject["findings"]!!.jsonArray
        assertEquals("use coerceAtMost(cap)", findings[0].jsonObject["suggestedFix"]!!.jsonPrimitive.content)
        assertFalse(findings[1].jsonObject.containsKey("suggestedFix"), "null suggestedFix should be omitted")
    }

    @Test
    fun `config embeds defaults for transparency`() {
        val config = parse()["config"]!!.jsonObject
        assertTrue(config.containsKey("maxConcurrency"), "encodeDefaults must surface default fields")
    }

    @Test
    fun `null fields are omitted not rendered as null`() {
        val result = RunResult(emptyList(), filesReviewed = 0, rulesRun = 0)
        val out = JsonReporter(ReviewsmithConfig(), clock).report(result)
        val meta = Json.parseToJsonElement(out).jsonObject["runMeta"]!!.jsonObject
        assertFalse(meta.containsKey("totalCostUsd"), "explicitNulls=false should omit null totalCostUsd")
    }
}
