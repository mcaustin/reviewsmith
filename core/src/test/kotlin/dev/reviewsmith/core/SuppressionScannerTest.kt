package dev.reviewsmith.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SuppressionScannerTest {

    private fun scan(text: String) = SuppressionScanner.scan("F.kt", text)

    @Test
    fun `parses disable-next-line with rule and reason`() {
        val d = scan("// reviewsmith-disable-next-line correctness-safety -- inputs are distinct").directives.single()
        assertEquals(SuppressionKind.NEXT_LINE, d.kind)
        assertEquals(setOf("correctness-safety"), d.ruleIds)
        assertEquals("inputs are distinct", d.reason)
        assertEquals(1, d.line)
    }

    @Test
    fun `parses all directive kinds`() {
        val text = """
            // reviewsmith-disable-next-line r1 -- a
            // reviewsmith-disable-line r2 -- b
            // reviewsmith-disable r3 -- c
            // reviewsmith-enable r3
            // reviewsmith-disable-file r4 -- d
        """.trimIndent()
        val kinds = scan(text).directives.map { it.kind }
        assertEquals(
            listOf(
                SuppressionKind.NEXT_LINE, SuppressionKind.SAME_LINE,
                SuppressionKind.BLOCK_DISABLE, SuppressionKind.BLOCK_ENABLE, SuppressionKind.FILE,
            ),
            kinds,
        )
    }

    @Test
    fun `recognizes hash and dash and block comment styles`() {
        assertEquals(1, scan("# reviewsmith-disable-line r -- x").directives.size)
        assertEquals(1, scan("-- reviewsmith-disable-line r -- x").directives.size)
        val block = scan("/* reviewsmith-disable-next-line r -- x */").directives.single()
        assertEquals(setOf("r"), block.ruleIds)
        assertEquals("x", block.reason)
    }

    @Test
    fun `block comment without a reason strips the closing marker from the rule id`() {
        val d = scan("/* reviewsmith-disable-line correctness */").directives.single()
        assertEquals(setOf("correctness"), d.ruleIds)
        assertNull(d.reason)
    }

    @Test
    fun `multiple rule ids parsed`() {
        val d = scan("// reviewsmith-disable r1,r2 -- both").directives.single()
        assertEquals(setOf("r1", "r2"), d.ruleIds)
    }

    @Test
    fun `bare directive is refused with a notice and not retained`() {
        val result = scan("// reviewsmith-disable-next-line")
        assertTrue(result.directives.isEmpty(), "a directive with no rule must not be honored")
        assertTrue(result.notices.any { it.contains("name a rule") }, "notices: ${result.notices}")
    }

    @Test
    fun `missing reason is honored but nagged`() {
        val result = scan("// reviewsmith-disable-next-line correctness-safety")
        assertEquals(1, result.directives.size, "still honored without a reason")
        assertNull(result.directives.single().reason)
        assertTrue(result.notices.any { it.contains("no reason") }, "notices: ${result.notices}")
    }

    @Test
    fun `enable directive may omit a rule id without a notice`() {
        val result = scan("// reviewsmith-enable")
        assertEquals(1, result.directives.size)
        assertTrue(result.notices.isEmpty(), "enable needs no rule/reason: ${result.notices}")
    }

    @Test
    fun `non-directive lines are ignored`() {
        assertTrue(scan("val x = 1 // just a comment\nfun f() {}").directives.isEmpty())
    }
}
