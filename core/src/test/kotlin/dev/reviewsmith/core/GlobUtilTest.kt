package dev.reviewsmith.core

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GlobUtilTest {

    @Test
    fun `leading double-star matches top-level and nested files`() {
        val m = GlobUtil.matcher("**/*.kt")
        assertTrue(m("A.kt"))
        assertTrue(m("src/main/A.kt"))
        assertFalse(m("A.java"))
    }

    @Test
    fun `mid-pattern double-star matches zero segments (gitignore semantics)`() {
        val m = GlobUtil.matcher("**/migration/**/*.sql")
        // file directly inside migration/ — Java's PathMatcher would miss this
        assertTrue(m("db/migration/V1__init.sql"))
        // file nested deeper inside migration/
        assertTrue(m("db/migration/2026/V2__x.sql"))
        // migration/ at the repo root
        assertTrue(m("migration/V3.sql"))
    }

    @Test
    fun `does not over-match unrelated paths`() {
        val m = GlobUtil.matcher("**/migration/**/*.sql")
        assertFalse(m("src/main/App.kt"))
        assertFalse(m("db/other/V1.sql"))
    }

    @Test
    fun `plain extension glob still works`() {
        val m = GlobUtil.matcher("**/*.sql")
        assertTrue(m("a/b/c.sql"))
        assertTrue(m("c.sql"))
    }
}
