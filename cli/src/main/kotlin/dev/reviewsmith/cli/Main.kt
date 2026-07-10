package dev.reviewsmith.cli

import dev.reviewsmith.core.ConsoleReporter
import dev.reviewsmith.core.Engine
import dev.reviewsmith.core.ReviewsmithConfig
import dev.reviewsmith.core.RuleResolver
import dev.reviewsmith.provider.claudecode.AgentUnavailableException
import dev.reviewsmith.provider.claudecode.ClaudeCodeProvider
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import kotlin.system.exitProcess

@Command(
    name = "reviewsmith",
    mixinStandardHelpOptions = true,
    version = ["reviewsmith 0.0.1"],
    subcommands = [BaselineCommand::class],
    description = ["AI-agent code review that reasons about intent."],
)
class ReviewsmithCommand : Callable<Int> {

    @Option(names = ["--scope"], description = ["changed (default) | full"])
    var scope: String? = null

    @Option(names = ["--root"], description = ["Repository root (default: current dir)"])
    var root: String = System.getProperty("user.dir")

    @Option(names = ["--model"], description = ["Model id to pass to the claude CLI"])
    var model: String? = null

    @Option(names = ["--no-color"], description = ["Disable ANSI color"])
    var noColor: Boolean = false

    @Option(names = ["--list-rules"], description = ["List the resolved rules and exit (no agent calls)"])
    var listRules: Boolean = false

    override fun call(): Int {
        val repoRoot = Path.of(root).toAbsolutePath().normalize()

        if (listRules) {
            val config = ReviewsmithConfig.load(repoRoot)
            val rules = RuleResolver.resolve(repoRoot, config)
            println("Rule sources: ${config.effectiveRuleSources().joinToString(", ")}")
            println("Resolved ${rules.size} rule(s):")
            for (r in rules) {
                val globs = if (r.appliesTo.isEmpty()) "all files" else r.appliesTo.joinToString(", ")
                println("  ${r.id}  [${r.severity}]  → $globs")
            }
            return 0
        }

        System.err.println("Reviewsmith: analyzing ${scope ?: "changed"} files in $repoRoot ...")

        val provider = ClaudeCodeProvider(model = model)
        val engine = Engine(provider)
        val result = try {
            engine.run(repoRoot, scope)
        } catch (e: AgentUnavailableException) {
            System.err.println("Reviewsmith: ${e.message}")
            System.err.println("Reviewsmith: skipping review (agent unavailable).")
            return 0
        }

        println(
            ConsoleReporter.report(
                result.findings,
                result.filesReviewed,
                suppressedByBaseline = result.suppressedByBaseline,
                abandonedUnits = result.abandonedUnits,
                useColor = !noColor,
            ),
        )

        result.totalCostUsd?.takeIf { it > 0 }?.let {
            System.err.println("Reviewsmith: run cost \$%.4f".format(it))
        }

        maybePrintBaselineTip(repoRoot, result.findings.size)

        // Advisory in this milestone: always exit 0 unless something errored.
        return 0
    }

    private fun maybePrintBaselineTip(repoRoot: Path, findingCount: Int) {
        if (findingCount == 0) return
        val config = ReviewsmithConfig.load(repoRoot)
        val effectiveScope = scope ?: config.scope.default
        if (effectiveScope != "full") return
        if (Files.exists(repoRoot.resolve(config.baseline.path))) return
        System.err.println(
            "Tip: run 'reviewsmith baseline' to snapshot these $findingCount finding(s) — " +
                "future runs will show only new issues.",
        )
    }
}

fun main(args: Array<String>) {
    exitProcess(CommandLine(ReviewsmithCommand()).execute(*args))
}
