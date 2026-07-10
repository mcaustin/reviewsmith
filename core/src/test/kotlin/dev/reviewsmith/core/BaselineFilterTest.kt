package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Files

class BaselineFilterTest {

    private fun finding(ruleId: String, file: String, line: Int? = null, message: String = "m") =
        Finding(ruleId = ruleId, file = file, line = line, severity = Severity.WARNING, message = message)

    private fun storeOf(vararg findings: Finding): BaselineStore {
        val entries = findings.groupingBy { Fingerprint.of(it) }.eachCount()
            .map { (fp, n) -> BaselineEntry(fp, "?", "?", n) }
        val tmp = Files.createTempFile("bl", ".json")
        Files.writeString(tmp, reviewsmithJson.encodeToString(BaselineFile.serializer(), BaselineFile(entries = entries)))
        return BaselineStore.load(tmp)
    }

    @Test
    fun `empty store surfaces all findings`() {
        val fs = listOf(finding("r", "A.kt"), finding("r", "B.kt"))
        val p = BaselineFilter.partition(fs, BaselineStore.empty())
        assertEquals(2, p.surfaced.size)
        assertEquals(0, p.suppressed.size)
    }

    @Test
    fun `matching entry suppresses up to count`() {
        val f = finding("r", "A.kt")
        val p = BaselineFilter.partition(listOf(f), storeOf(f))
        assertEquals(0, p.surfaced.size)
        assertEquals(1, p.suppressed.size)
    }

    @Test
    fun `count of two suppresses two and surfaces third`() {
        val f = finding("r", "A.kt")
        val store = storeOf(f, f) // count = 2
        val current = listOf(finding("r", "A.kt", 1), finding("r", "A.kt", 2), finding("r", "A.kt", 3))
        val p = BaselineFilter.partition(current, store)
        assertEquals(2, p.suppressed.size)
        assertEquals(1, p.surfaced.size)
    }

    @Test
    fun `count exceeding occurrences is benign`() {
        val f = finding("r", "A.kt")
        val store = storeOf(f, f, f, f, f) // count = 5
        val p = BaselineFilter.partition(listOf(finding("r", "A.kt"), finding("r", "A.kt")), store)
        assertEquals(0, p.surfaced.size)
        assertEquals(2, p.suppressed.size)
    }

    @Test
    fun `stale entry does not affect unrelated findings`() {
        val store = storeOf(finding("ruleA", "fileA.kt"))
        val p = BaselineFilter.partition(listOf(finding("ruleB", "fileB.kt")), store)
        assertEquals(1, p.surfaced.size)
        assertEquals(0, p.suppressed.size)
    }

    @Test
    fun `different files are independent buckets`() {
        val store = storeOf(finding("r", "A.kt")) // only A baselined
        val p = BaselineFilter.partition(listOf(finding("r", "A.kt"), finding("r", "B.kt")), store)
        assertEquals(1, p.surfaced.size)
        assertEquals("B.kt", p.surfaced[0].file)
        assertEquals(1, p.suppressed.size)
    }
}
