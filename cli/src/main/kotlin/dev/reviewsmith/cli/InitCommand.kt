package dev.reviewsmith.cli

import dev.reviewsmith.core.BuildInfo
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

@Command(
    name = "init",
    mixinStandardHelpOptions = true,
    description = ["Scaffold a starter reviewsmith.yml and print the Gradle plugin wiring."],
)
class InitCommand : Callable<Int> {

    @Option(names = ["--root"], description = ["Repository root (default: current dir)"])
    var root: String = System.getProperty("user.dir")

    @Option(names = ["--apply"], description = ["Write reviewsmith.yml (only that file; never the build script)"])
    var apply: Boolean = false

    @Option(names = ["--force"], description = ["Overwrite an existing reviewsmith.yml"])
    var force: Boolean = false

    override fun call(): Int {
        val repoRoot = Path.of(root).toAbsolutePath().normalize()
        val configPath = repoRoot.resolve("reviewsmith.yml")

        if (apply) {
            if (Files.exists(configPath) && !force) {
                System.err.println("reviewsmith.yml already exists — pass --force to overwrite.")
                return 0
            }
            Files.writeString(configPath, STARTER_CONFIG)
            println("Wrote ${configPath.fileName} to $repoRoot")
        } else {
            println("# Add to your build.gradle.kts (JitPack — no mavenLocal needed):")
            println(pluginSnippet())
            println()
            println("# Starter reviewsmith.yml (run 'reviewsmith init --apply' to write it):")
            println(STARTER_CONFIG)
        }

        println()
        println("Next steps:")
        println("  1. ./gradlew reviewsmith        # see your first findings")
        println("  2. reviewsmith baseline         # snapshot pre-existing issues (optional)")
        return 0
    }

    private fun pluginSnippet(): String = """
        buildscript {
            repositories { maven(url = "https://jitpack.io") }
            dependencies { classpath("com.github.mcaustin.reviewsmith:gradle-plugin:${BuildInfo.version}") }
        }
        apply(plugin = "dev.reviewsmith")
    """.trimIndent()

    private companion object {
        val STARTER_CONFIG = """
            scope:
              default: changed          # changed (diff vs base) | full
              # baseRef: origin/main    # force a base; auto-detected when unset
            # gate stays advisory (exit 0) until you opt in:
            gate:
              failOn: none              # none | warning | error
            # cache zeroes re-run cost once you pin a model:
            cache:
              enabled: false            # set true and pass --model to cache
        """.trimIndent() + "\n"
    }
}
