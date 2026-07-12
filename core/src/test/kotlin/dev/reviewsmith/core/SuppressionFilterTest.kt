package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuppressionFilterTest {

    private fun finding(rule: String, line: Int?, file: String = "F.kt") =
        Finding(ruleId = rule, file = file, line = line, severity = Severity.ERROR, message = "boom")

    private fun scan(file: String, text: String) = file to SuppressionScanner.scan(file, text)

    private fun apply(findings: List<Finding>, vararg files: Pair<String, FileSuppressions>, band: Int = 2) =
        SuppressionFilter.apply(findings, files.toMap(), band)

    @Test
    fun `next-line suppresses a matching finding on the following line`() {
        val fs = scan("F.kt", "// reviewsmith-disable-next-line correctness -- ok\nval x = risky()")
        val res = apply(listOf(finding("correctness", 2)), fs)
        assertEquals(1, res.suppressed.size)
        assertTrue(res.surfaced.isEmpty())
    }

    @Test
    fun `next-line tolerates line drift within the band`() {
        val fs = scan("F.kt", "// reviewsmith-disable-next-line correctness -- ok\n\n\nval x = risky()")
        // directive on line 1, target line 2, finding drifts to line 4 -> within +2 band
        val res = apply(listOf(finding("correctness", 4)), fs, band = 2)
        assertEquals(1, res.suppressed.size)
    }

    @Test
    fun `next-line does not suppress beyond the band`() {
        val fs = scan("F.kt", "// reviewsmith-disable-next-line correctness -- ok")
        val res = apply(listOf(finding("correctness", 10)), fs, band = 2)
        assertEquals(1, res.surfaced.size)
        assertTrue(res.suppressed.isEmpty())
    }

    @Test
    fun `directive only suppresses the named rule`() {
        val fs = scan("F.kt", "// reviewsmith-disable-next-line correctness -- ok\nval x = risky()")
        val res = apply(listOf(finding("correctness", 2), finding("style", 2)), fs)
        assertEquals(1, res.suppressed.size)
        assertEquals(listOf("style"), res.surfaced.map { it.ruleId })
    }

    @Test
    fun `file directive suppresses every matching finding regardless of line`() {
        val fs = scan("F.kt", "// reviewsmith-disable-file correctness -- legacy")
        val res = apply(listOf(finding("correctness", 5), finding("correctness", 500), finding("correctness", null)), fs)
        assertEquals(3, res.suppressed.size)
    }

    @Test
    fun `block suppresses findings within the disable-enable range only`() {
        val text = buildString {
            appendLine("line1")                                              // 1
            appendLine("// reviewsmith-disable correctness -- region")       // 2
            appendLine("risky1")                                             // 3
            appendLine("risky2")                                             // 4
            appendLine("// reviewsmith-enable correctness")                  // 5
            appendLine("risky3")                                             // 6
        }
        val fs = scan("F.kt", text)
        val res = apply(listOf(finding("correctness", 3), finding("correctness", 4), finding("correctness", 6)), fs)
        assertEquals(setOf(3, 4), res.suppressed.mapNotNull { it.line }.toSet())
        assertEquals(listOf(6), res.surfaced.mapNotNull { it.line })
    }

    @Test
    fun `open block with no enable runs to end of file`() {
        val fs = scan("F.kt", "// reviewsmith-disable correctness -- rest of file\nrisky\nmore")
        val res = apply(listOf(finding("correctness", 2), finding("correctness", 99)), fs)
        assertEquals(2, res.suppressed.size)
    }

    @Test
    fun `unused directive is reported`() {
        val fs = scan("F.kt", "// reviewsmith-disable-next-line correctness -- ok\nval x = safe()")
        val res = apply(listOf(finding("style", 2)), fs)
        assertTrue(res.surfaced.size == 1)
        assertTrue(res.notices.any { it.contains("unused suppression") && it.contains("correctness") }, "notices: ${res.notices}")
    }

    @Test
    fun `a used directive produces no unused notice`() {
        val fs = scan("F.kt", "// reviewsmith-disable-next-line correctness -- ok\nval x = risky()")
        val res = apply(listOf(finding("correctness", 2)), fs)
        assertTrue(res.notices.none { it.contains("unused") }, "notices: ${res.notices}")
    }

    @Test
    fun `finding in an unmatched file is untouched`() {
        val fs = scan("A.kt", "// reviewsmith-disable-file correctness -- x")
        val res = apply(listOf(finding("correctness", 2, file = "B.kt")), fs)
        assertEquals(1, res.surfaced.size)
    }

    @Test
    fun `no directives passes findings through`() {
        val res = apply(listOf(finding("correctness", 2)), scan("F.kt", "val x = 1"))
        assertEquals(1, res.surfaced.size)
        assertTrue(res.suppressed.isEmpty())
    }
}
