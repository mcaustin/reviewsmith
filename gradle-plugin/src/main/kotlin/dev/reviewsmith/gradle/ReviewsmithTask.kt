package dev.reviewsmith.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Extracts the bundled Reviewsmith CLI jar and runs it in a separate JVM via
 * [ExecOperations] inside the task action — the configuration-cache-safe pattern.
 */
abstract class ReviewsmithTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:Input
    @get:Optional
    abstract val scope: Property<String>

    @get:Input
    @get:Optional
    abstract val model: Property<String>

    @get:Internal
    abstract val projectRootDir: DirectoryProperty

    @TaskAction
    fun run() {
        val cliJar = extractCli()
        val root = projectRootDir.get().asFile

        val args = buildList {
            add("-jar"); add(cliJar.absolutePath)
            add("--root"); add(root.absolutePath)
            if (scope.isPresent) { add("--scope"); add(scope.get()) }
            if (model.isPresent) { add("--model"); add(model.get()) }
        }

        execOperations.exec { spec ->
            spec.executable = javaExecutable()
            spec.args = args
            spec.isIgnoreExitValue = true
        }
    }

    private fun extractCli(): File {
        val stream = javaClass.getResourceAsStream("/cli/reviewsmith-cli.jar")
            ?: error("Reviewsmith CLI jar not bundled in the plugin.")
        val target = File(temporaryDir, "reviewsmith-cli.jar")
        stream.use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun javaExecutable(): String {
        val javaHome = System.getProperty("java.home")
        return File(File(javaHome, "bin"), "java").absolutePath
    }
}
