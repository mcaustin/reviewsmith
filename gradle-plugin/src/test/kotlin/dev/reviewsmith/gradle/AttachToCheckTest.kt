package dev.reviewsmith.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AttachToCheckTest {

    private fun project(dir: File, attach: Boolean?) {
        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"consumer\"\n")
        val attachLine = attach?.let { "reviewsmith { attachToCheck.set($it) }\n" } ?: ""
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                base
                id("dev.reviewsmith")
            }
            $attachLine
            """.trimIndent(),
        )
    }

    private fun tasksOutput(dir: File): String =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments("check", "--dry-run")
            .build()
            .output

    @Test
    fun `reviewsmith runs as part of check when attachToCheck is true`(@TempDir dir: File) {
        project(dir, attach = true)
        assertTrue(tasksOutput(dir).contains(":reviewsmith "), "reviewsmith should be in check's graph")
    }

    @Test
    fun `reviewsmith is not part of check by default`(@TempDir dir: File) {
        project(dir, attach = null)
        assertTrue(!tasksOutput(dir).contains(":reviewsmith "), "reviewsmith must not attach by default")
    }
}
