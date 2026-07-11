package dev.reviewsmith.gradle

import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ConfigurationCacheTest {

    private fun gitRepo(dir: File) {
        fun git(vararg args: String) {
            val code = ProcessBuilder(listOf("git", *args))
                .directory(dir)
                .redirectErrorStream(true)
                .start()
                .waitFor()
            check(code == 0) { "git ${args.joinToString(" ")} failed ($code)" }
        }
        git("init", "-q")
        git("config", "user.email", "test@example.com")
        git("config", "user.name", "test")
        git("commit", "-q", "--allow-empty", "-m", "init")

        File(dir, "settings.gradle.kts").writeText("rootProject.name = \"consumer\"\n")
        File(dir, "build.gradle.kts").writeText(
            """
            plugins {
                base
                id("io.github.mcaustin.reviewsmith")
            }
            """.trimIndent(),
        )
    }

    private fun run(dir: File) =
        GradleRunner.create()
            .withProjectDir(dir)
            .withPluginClasspath()
            .withArguments("reviewsmith", "--configuration-cache")
            .build()

    @Test
    fun `reviewsmith task is configuration-cache compatible`(@TempDir dir: File) {
        gitRepo(dir)

        val first = run(dir).output
        assertTrue(
            first.contains("Configuration cache entry stored") && !first.contains("problems were found"),
            "first run should store a CC entry with no problems:\n$first",
        )

        val second = run(dir).output
        assertTrue(
            second.contains("Configuration cache entry reused"),
            "second run should reuse the stored CC entry:\n$second",
        )
    }
}
