package dev.reviewsmith.core

import dev.reviewsmith.spi.Finding
import dev.reviewsmith.spi.Severity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class FingerprintTest {

    private fun finding(
        ruleId: String = "r",
        file: String = "src/A.kt",
        line: Int? = 1,
        message: String = "m",
    ) = Finding(ruleId = ruleId, file = file, line = line, severity = Severity.WARNING, message = message)

    @Test
    fun `produces a 12 hex char string`() {
        val fp = Fingerprint.of(finding())
        assertEquals(12, fp.length)
        assertTrue(fp.all { it in "0123456789abcdef" }, "not hex: $fp")
    }

    @Test
    fun `is stable for the same rule and file`() {
        assertEquals(Fingerprint.of(finding()), Fingerprint.of(finding()))
    }

    @Test
    fun `is stable regardless of message and line drift`() {
        val a = finding(line = 42, message = "Null-dereference at line 42 in foo()")
        val b = finding(line = 99, message = "possible NPE when the map lookup returns null")
        assertEquals(Fingerprint.of(a), Fingerprint.of(b))
    }

    @Test
    fun `distinguishes different files`() {
        assertNotEquals(Fingerprint.of(finding(file = "src/A.kt")), Fingerprint.of(finding(file = "src/B.kt")))
    }

    @Test
    fun `distinguishes different rules`() {
        assertNotEquals(Fingerprint.of(finding(ruleId = "x")), Fingerprint.of(finding(ruleId = "y")))
    }

    @Test
    fun `normalizeFile converts backslashes and strips drive prefix`() {
        assertEquals("src/A.kt", Fingerprint.normalizeFile("src\\A.kt"))
        assertEquals("src/A.kt", Fingerprint.normalizeFile("C:/src/A.kt"))
        assertEquals("src/A.kt", Fingerprint.normalizeFile("/src/A.kt"))
    }
}
