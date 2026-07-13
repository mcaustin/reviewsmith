package dev.reviewsmith.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.io.FileOutputStream
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

    @get:Input
    @get:Optional
    abstract val failOnGate: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val format: Property<String>

    @get:Input
    @get:Optional
    abstract val isolation: Property<String>

    @get:Input
    @get:Optional
    abstract val noCache: Property<Boolean>

    @get:Input
    @get:Optional
    abstract val maxUnits: Property<Int>

    @get:Input
    @get:Optional
    abstract val reportLevel: Property<String>

    @get:OutputFile
    @get:Optional
    abstract val outputFile: RegularFileProperty

    @get:Internal
    abstract val projectRootDir: DirectoryProperty

    @TaskAction
    fun run() {
        val cliJar = extractCli()
        val root = projectRootDir.get().asFile

        val args = buildCliArgs(
            cliJarPath = cliJar.absolutePath,
            rootPath = root.absolutePath,
            scope = scope.orNull,
            model = model.orNull,
            format = format.orNull,
            isolation = isolation.orNull,
            noCache = noCache.getOrElse(false),
            maxUnits = maxUnits.orNull,
            reportLevel = reportLevel.orNull,
        )
        val reportSink = outputFile.orNull?.asFile?.takeIf { redirectsStdout(format.orNull) }
        reportSink?.parentFile?.mkdirs()

        val exitCode = (reportSink?.let { FileOutputStream(it) }).use { sink ->
            execOperations.exec { spec ->
                spec.executable = javaExecutable()
                spec.args = args
                spec.isIgnoreExitValue = true
                if (sink != null) spec.standardOutput = sink
            }.exitValue
        }

        when (gateOutcome(exitCode, failOnGate.getOrElse(true))) {
            Outcome.OK -> Unit
            Outcome.GATE_ADVISORY ->
                logger.warn("Reviewsmith gate triggered (exit 3), but failOnGate=false — not failing the build.")
            Outcome.GATE_FAIL ->
                throw GradleException("Reviewsmith gate failed — findings exceed the configured threshold. See output above.")
            Outcome.ERROR ->
                throw GradleException("Reviewsmith CLI exited with code $exitCode. See output above.")
        }
    }

    enum class Outcome { OK, GATE_FAIL, GATE_ADVISORY, ERROR }

    companion object {
        fun gateOutcome(exitCode: Int, failOnGate: Boolean): Outcome = when (exitCode) {
            0 -> Outcome.OK
            3 -> if (failOnGate) Outcome.GATE_FAIL else Outcome.GATE_ADVISORY
            else -> Outcome.ERROR
        }

        /** stdout is redirected to the output file only for machine formats (not console). */
        fun redirectsStdout(format: String?): Boolean =
            format?.lowercase() == "json" || format?.lowercase() == "sarif"

        fun buildCliArgs(
            cliJarPath: String,
            rootPath: String,
            scope: String?,
            model: String?,
            format: String?,
            isolation: String?,
            noCache: Boolean,
            maxUnits: Int? = null,
            reportLevel: String? = null,
        ): List<String> = buildList {
            add("-jar"); add(cliJarPath)
            add("--root"); add(rootPath)
            if (scope != null) { add("--scope"); add(scope) }
            if (model != null) { add("--model"); add(model) }
            if (format != null) { add("--format"); add(format) }
            if (isolation != null) { add("--isolation"); add(isolation) }
            if (noCache) add("--no-cache")
            if (maxUnits != null) { add("--max-units"); add(maxUnits.toString()) }
            if (reportLevel != null) { add("--report-level"); add(reportLevel) }
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
