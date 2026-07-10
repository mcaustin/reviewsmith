package dev.reviewsmith.cli

import dev.reviewsmith.core.BaselineStore
import dev.reviewsmith.core.BaselineWriter
import dev.reviewsmith.core.Engine
import dev.reviewsmith.core.ReviewsmithConfig
import dev.reviewsmith.provider.claudecode.AgentUnavailableException
import dev.reviewsmith.provider.claudecode.ClaudeCodeProvider
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.util.concurrent.Callable

@Command(
    name = "baseline",
    mixinStandardHelpOptions = true,
    description = ["Snapshot current findings to a baseline; later runs show only new findings."],
)
class BaselineCommand : Callable<Int> {

    @Option(names = ["--root"], description = ["Repository root (default: current dir)"])
    var root: String = System.getProperty("user.dir")

    @Option(names = ["--model"], description = ["Model id to pass to the claude CLI"])
    var model: String? = null

    override fun call(): Int {
        val repoRoot = Path.of(root).toAbsolutePath().normalize()
        val config = ReviewsmithConfig.load(repoRoot)
        val baselinePath = repoRoot.resolve(config.baseline.path)

        System.err.println("Reviewsmith baseline: full scan of $repoRoot ...")

        val provider = ClaudeCodeProvider(model = model)
        val result = try {
            Engine(provider).run(repoRoot, mode = "full", baselineStore = BaselineStore.empty())
        } catch (e: AgentUnavailableException) {
            System.err.println("Reviewsmith: ${e.message}")
            System.err.println("Reviewsmith: cannot write baseline (agent unavailable).")
            return 0
        }

        return try {
            val entries = BaselineWriter.write(result.findings, baselinePath, Instant.now(Clock.systemUTC()).toString())
            println(
                "Reviewsmith baseline: found ${result.findings.size} finding(s) across " +
                    "${result.rulesRun} rule(s). Wrote ${config.baseline.path} ($entries entries).",
            )
            0
        } catch (e: Exception) {
            System.err.println("Reviewsmith: failed to write baseline: ${e.message}")
            1
        }
    }
}
