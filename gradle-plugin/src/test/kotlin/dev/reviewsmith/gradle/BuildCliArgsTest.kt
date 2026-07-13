package dev.reviewsmith.gradle

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BuildCliArgsTest {

    @Test
    fun `only jar and root when nothing else set`() {
        val args = ReviewsmithTask.buildCliArgs(
            cliJarPath = "/x/cli.jar", rootPath = "/repo",
            scope = null, model = null, format = null, isolation = null, noCache = false,
        )
        assertEquals(listOf("-jar", "/x/cli.jar", "--root", "/repo"), args)
    }

    @Test
    fun `optional flags are appended when set`() {
        val args = ReviewsmithTask.buildCliArgs(
            cliJarPath = "/x/cli.jar", rootPath = "/repo",
            scope = "changed", model = "claude-opus-4-8", format = "sarif", isolation = "local", noCache = true,
        )
        assertTrue(args.containsAll(listOf("--scope", "changed", "--model", "claude-opus-4-8", "--format", "sarif", "--isolation", "local", "--no-cache")))
    }

    @Test
    fun `no-cache flag is a bare switch omitted when false`() {
        val args = ReviewsmithTask.buildCliArgs(
            cliJarPath = "j", rootPath = "r",
            scope = null, model = null, format = null, isolation = null, noCache = false,
        )
        assertFalse(args.contains("--no-cache"))
    }

    @Test
    fun `maxUnits is appended when set and omitted when null`() {
        val withMax = ReviewsmithTask.buildCliArgs(
            cliJarPath = "j", rootPath = "r",
            scope = null, model = null, format = null, isolation = null, noCache = false, maxUnits = 200,
        )
        assertTrue(withMax.containsAll(listOf("--max-units", "200")))
        val without = ReviewsmithTask.buildCliArgs(
            cliJarPath = "j", rootPath = "r",
            scope = null, model = null, format = null, isolation = null, noCache = false,
        )
        assertFalse(without.contains("--max-units"))
    }

    @Test
    fun `stdout is redirected only for machine formats`() {
        assertTrue(ReviewsmithTask.redirectsStdout("json"))
        assertTrue(ReviewsmithTask.redirectsStdout("sarif"))
        assertFalse(ReviewsmithTask.redirectsStdout("console"))
        assertFalse(ReviewsmithTask.redirectsStdout(null))
    }
}
