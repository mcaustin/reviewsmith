package dev.reviewsmith.cli

import dev.reviewsmith.core.CacheStore
import dev.reviewsmith.core.ConsoleReporter
import dev.reviewsmith.core.Engine
import dev.reviewsmith.core.GateDecision
import dev.reviewsmith.core.GateEvaluator
import dev.reviewsmith.core.JsonReporter
import dev.reviewsmith.core.Reporter
import dev.reviewsmith.core.ReviewsmithConfig
import dev.reviewsmith.core.RuleResolver
import dev.reviewsmith.core.SarifReporter
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

    @Option(names = ["--no-cache"], description = ["Bypass cache reads and writes for this run"])
    var noCache: Boolean = false

    @Option(names = ["--refresh"], description = ["Bypass cache reads; write fresh results back to cache"])
    var refresh: Boolean = false

    @Option(names = ["--format"], description = ["console (default) | json | sarif"])
    var format: String = "console"

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

        val config = ReviewsmithConfig.load(repoRoot)
        val cacheStore = when {
            noCache -> CacheStore.noOp()
            refresh -> CacheStore.refreshMode(config.cache, repoRoot)
            else -> null
        }

        val provider = ClaudeCodeProvider(model = model)
        val engine = Engine(provider)
        val result = try {
            engine.run(repoRoot, scope, cacheStore = cacheStore)
        } catch (e: AgentUnavailableException) {
            System.err.println("Reviewsmith: ${e.message}")
            System.err.println("Reviewsmith: skipping review (agent unavailable).")
            return 0
        }

        val reporter: Reporter = when (format.lowercase()) {
            "json" -> JsonReporter(config)
            "sarif" -> SarifReporter()
            else -> ConsoleReporter(useColor = !noColor)
        }
        println(reporter.report(result))

        result.totalCostUsd?.let {
            val hits = if (result.cacheHits > 0) " (${result.cacheHits} cache hit(s))" else ""
            System.err.println("Reviewsmith: run cost \$%.4f".format(it) + hits)
        }

        maybePrintBaselineTip(repoRoot, result.findings.size)

        val gateResult = GateEvaluator.evaluate(result.findings, config.gate, result.rulesById)
        gateResult.warnings.forEach { System.err.println(it) }
        val decision = gateResult.decision
        if (decision is GateDecision.Fail) {
            System.err.println(
                "Reviewsmith: gate triggered — ${decision.triggeringFindings.size} finding(s) exceed the configured threshold.",
            )
            return 3
        }
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
