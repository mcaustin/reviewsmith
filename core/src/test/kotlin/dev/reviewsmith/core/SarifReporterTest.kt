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

class SarifReporterTest {

    private fun result(findings: List<Finding>, rulesById: Map<String, Rule> = emptyMap()) =
        RunResult(findings, filesReviewed = 1, rulesRun = 1, rulesById = rulesById)

    private fun parse(sarif: String) = Json.parseToJsonElement(sarif).jsonObject

    private fun results(sarif: String) =
        parse(sarif)["runs"]!!.jsonArray[0].jsonObject["results"]!!.jsonArray

    @Test
    fun `emits valid top-level SARIF structure`() {
        val out = SarifReporter().report(
            result(listOf(Finding("r", "A.kt", 5, Severity.ERROR, "boom"))),
        )
        val root = parse(out)
        assertEquals("2.1.0", root["version"]!!.jsonPrimitive.content)
        assertTrue(root.containsKey("\$schema"))
        val driver = root["runs"]!!.jsonArray[0].jsonObject["tool"]!!.jsonObject["driver"]!!.jsonObject
        assertEquals("Reviewsmith", driver["name"]!!.jsonPrimitive.content)
        assertTrue(driver["version"]!!.jsonPrimitive.content.isNotBlank(), "driver version must be present")
    }

    @Test
    fun `maps severity to sarif level`() {
        val out = SarifReporter().report(
            result(
                listOf(
                    Finding("r", "A.kt", 1, Severity.ERROR, "e"),
                    Finding("r", "B.kt", 1, Severity.WARNING, "w"),
                    Finding("r", "C.kt", 1, Severity.INFO, "i"),
                ),
            ),
        )
        val levels = results(out).map { it.jsonObject["level"]!!.jsonPrimitive.content }
        assertEquals(listOf("error", "warning", "note"), levels)
    }

    @Test
    fun `line null or zero omits the region`() {
        val out = SarifReporter().report(
            result(
                listOf(
                    Finding("r", "A.kt", null, Severity.ERROR, "e"),
                    Finding("r", "B.kt", 0, Severity.WARNING, "w"),
                ),
            ),
        )
        results(out).forEach { r ->
            val loc = r.jsonObject["locations"]!!.jsonArray[0].jsonObject["physicalLocation"]!!.jsonObject
            assertFalse(loc.containsKey("region"), "region must be omitted for null/zero line: $loc")
        }
    }

    @Test
    fun `positive line emits a 1-based region`() {
        val out = SarifReporter().report(result(listOf(Finding("r", "A.kt", 7, Severity.ERROR, "e"))))
        val loc = results(out)[0].jsonObject["locations"]!!.jsonArray[0].jsonObject["physicalLocation"]!!.jsonObject
        assertEquals(7, loc["region"]!!.jsonObject["startLine"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `driver rules carry the rule name when known`() {
        val out = SarifReporter().report(
            result(
                listOf(Finding("correctness", "A.kt", 1, Severity.ERROR, "e")),
                rulesById = mapOf("correctness" to Rule("correctness", "Correctness & Safety", Severity.ERROR, emptyList(), emptyList(), emptyList(), "b")),
            ),
        )
        val rules = parse(out)["runs"]!!.jsonArray[0].jsonObject["tool"]!!.jsonObject["driver"]!!.jsonObject["rules"]!!.jsonArray
        assertEquals("correctness", rules[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("Correctness & Safety", rules[0].jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `backslash paths are normalized to forward slashes`() {
        val out = SarifReporter().report(result(listOf(Finding("r", "src\\main\\A.kt", 1, Severity.ERROR, "e"))))
        val uri = results(out)[0].jsonObject["locations"]!!.jsonArray[0].jsonObject["physicalLocation"]!!
            .jsonObject["artifactLocation"]!!.jsonObject["uri"]!!.jsonPrimitive.content
        assertEquals("src/main/A.kt", uri)
    }
}
