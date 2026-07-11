package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

class BaselineRoundTripTest {

    private fun finding(ruleId: String, file: String, line: Int? = null) =
        Finding(ruleId = ruleId, file = file, line = line, severity = Severity.WARNING, message = "m")

    @Test
    fun `write then load preserves fingerprint counts`(@TempDir dir: Path) {
        val path = dir.resolve("reviewsmith-baseline.json")
        val findings = listOf(
            finding("r", "A.kt", 1),
            finding("r", "A.kt", 2),
            finding("r", "B.kt"),
        )
        BaselineWriter.write(findings, path, "2026-07-10T00:00:00Z")

        val store = BaselineStore.load(path)
        assertEquals(2, store.countFor(Fingerprint.of(finding("r", "A.kt"))))
        assertEquals(1, store.countFor(Fingerprint.of(finding("r", "B.kt"))))
    }

    @Test
    fun `output is stable regardless of input order`(@TempDir dir: Path) {
        val a = finding("r", "A.kt")
        val b = finding("z", "B.kt")
        val p1 = dir.resolve("b1.json")
        val p2 = dir.resolve("b2.json")
        BaselineWriter.write(listOf(a, b), p1, "T")
        BaselineWriter.write(listOf(b, a), p2, "T")
        assertEquals(Files.readString(p1), Files.readString(p2))
    }

    @Test
    fun `corrupt json loads as empty store`(@TempDir dir: Path) {
        val path = dir.resolve("bad.json")
        Files.writeString(path, "{ not valid json ")
        val store = BaselineStore.load(path)
        assertEquals(0, store.countFor("anything"))
    }

    @Test
    fun `version mismatch loads as empty store`(@TempDir dir: Path) {
        val path = dir.resolve("v99.json")
        Files.writeString(
            path,
            reviewsmithJson.encodeToString(
                BaselineFile.serializer(),
                BaselineFile(version = 99, entries = listOf(BaselineEntry("fp", "r", "A.kt", 3))),
            ),
        )
        assertEquals(0, BaselineStore.load(path).countFor("fp"))
    }

    @Test
    fun `missing file loads as empty store`(@TempDir dir: Path) {
        assertEquals(0, BaselineStore.load(dir.resolve("nope.json")).countFor("fp"))
    }

    @Test
    fun `write creates missing parent directories`(@TempDir dir: Path) {
        val path = dir.resolve("reports/nested/reviewsmith-baseline.json")
        BaselineWriter.write(listOf(finding("r", "A.kt")), path, "T")
        assertTrue(Files.exists(path), "baseline should be written even when parent dirs are absent")
    }

    @Test
    fun `written entries are sorted by rule then file`(@TempDir dir: Path) {
        val path = dir.resolve("sorted.json")
        BaselineWriter.write(
            listOf(finding("zebra", "Z.kt"), finding("alpha", "B.kt"), finding("alpha", "A.kt")),
            path,
            "T",
        )
        val text = Files.readString(path)
        val alphaA = text.indexOf("A.kt")
        val alphaB = text.indexOf("B.kt")
        val zebra = text.indexOf("Z.kt")
        assertTrue(alphaA < alphaB && alphaB < zebra, "entries not sorted: $text")
    }
}
