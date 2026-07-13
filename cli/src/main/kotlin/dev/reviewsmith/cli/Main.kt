package dev.reviewsmith.cli

import dev.reviewsmith.core.CacheStore
import dev.reviewsmith.core.ConsoleReporter
import dev.reviewsmith.core.Engine
import dev.reviewsmith.core.GateConfig
import dev.reviewsmith.core.GateDecision
import dev.reviewsmith.core.GateEvaluator
import dev.reviewsmith.core.GlobUtil
import dev.reviewsmith.core.JsonReporter
import dev.reviewsmith.core.Reporter
import dev.reviewsmith.core.ReviewsmithConfig
import dev.reviewsmith.core.RuleResolver
import dev.reviewsmith.core.SarifReporter
import dev.reviewsmith.core.ScopeExceededException
import dev.reviewsmith.core.ScopeResolver
import dev.reviewsmith.provider.claudecode.AgentUnavailableException
import dev.reviewsmith.provider.claudecode.ClaudeCodeProvider
import dev.reviewsmith.spi.AgentProviderFactory
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
    version = ["reviewsmith 0.2.0"],
    subcommands = [BaselineCommand::class, InitCommand::class],
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

    @Option(names = ["--isolation"], description = ["strict (default, hermetic) | local (apply your local Claude config)"])
    var isolation: String? = null

    @Option(names = ["--rule"], description = ["Run only this rule id (repeatable); overrides config onlyRules"])
    var rule: List<String>? = null

    @Option(names = ["--fail-on"], description = ["Gate threshold: none | warning | error (overrides config)"])
    var failOn: String? = null

    @Option(names = ["--only-confidence"], description = ["Gate only findings at this confidence: clear | ambiguous | all"])
    var onlyConfidence: String? = null

    @Option(
        names = ["--fail-on-category"],
        arity = "0..*",
        description = ["Gate findings whose rule carries any of these tags (overrides config)"],
    )
    var failOnCategory: List<String>? = null

    @Option(names = ["--dry-run"], description = ["Print the scope, rule count, and cost estimate, then exit (no agent calls)"])
    var dryRun: Boolean = false

    @Option(names = ["--fail-on-abandoned"], description = ["Fail (exit 3) if any unit was abandoned (timeout/budget)"])
    var failOnAbandoned: Boolean = false

    @Option(names = ["--report-level"], description = ["Hide findings below this severity: info (default) | warning | error"])
    var reportLevel: String? = null

    @Option(names = ["--no-diff"], description = ["Do not embed the changed-lines diff in prompts (agent reads whole files)"])
    var noDiff: Boolean = false

    @Option(names = ["--no-inline-suppression"], description = ["Ignore // reviewsmith-disable directives in source"])
    var noInlineSuppression: Boolean = false

    @Option(names = ["--force"], description = ["Bypass the scope.maxUnits guardrail for this run"])
    var force: Boolean = false

    @Option(names = ["--max-units"], description = ["Abort before any agent call if scope exceeds this many (rule × file) units (0 = unlimited)"])
    var maxUnits: Int? = null

    override fun call(): Int {
        val repoRoot = Path.of(root).toAbsolutePath().normalize()

        if (listRules) {
            val config = withCliOverrides(ReviewsmithConfig.load(repoRoot))
            val rules = RuleResolver.resolve(repoRoot, config)
            println("Rule sources: ${config.effectiveRuleSources().joinToString(", ")}")
            println("Resolved ${rules.size} rule(s):")
            for (r in rules) {
                val globs = if (r.appliesTo.isEmpty()) "all files" else r.appliesTo.joinToString(", ")
                println("  ${r.id}  [${r.severity}]  → $globs")
            }
            return 0
        }

        val config = withCliOverrides(ReviewsmithConfig.load(repoRoot))

        if (dryRun) return printDryRun(repoRoot, config)

        System.err.println("Reviewsmith: analyzing ${scope ?: "changed"} files in $repoRoot ...")

        val cacheStore = when {
            noCache -> CacheStore.noOp()
            refresh -> CacheStore.refreshMode(config.cache, repoRoot)
            else -> null
        }

        val hermetic = when (isolation?.lowercase()) {
            "local" -> false
            "strict" -> true
            else -> config.agent.hermetic()
        }
        val provider = AgentProviderFactory.resolve()?.create(model, hermetic)
            ?: ClaudeCodeProvider(model = model, hermetic = hermetic)
        val engine = Engine(provider)
        val result = try {
            engine.run(repoRoot, scope, cacheStore = cacheStore, config = config)
        } catch (e: AgentUnavailableException) {
            System.err.println("Reviewsmith: ${e.message}")
            System.err.println("Reviewsmith: skipping review (agent unavailable).")
            return 0
        } catch (e: ScopeExceededException) {
            System.err.println("Reviewsmith: ${e.message}")
            return 2
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

        maybePrintBaselineTip(config, repoRoot, result.findings.size)

        val effectiveGate = mergeGate(config.gate)
        val gateResult = GateEvaluator.evaluate(result.findings, effectiveGate, result.rulesById)
        gateResult.warnings.forEach { System.err.println(it) }
        val decision = gateResult.decision
        if (decision is GateDecision.Fail) {
            System.err.println(
                "Reviewsmith: gate triggered — ${decision.triggeringFindings.size} finding(s) exceed the configured threshold.",
            )
            return 3
        }
        if (effectiveGate.failOnAbandoned && result.abandonedUnits > 0) {
            System.err.println(
                "Reviewsmith: gate triggered — ${result.abandonedUnits} unit(s) were abandoned " +
                    "(timeout/budget) and failOnAbandoned is set; the review is incomplete.",
            )
            return 3
        }
        return 0
    }

    private fun withCliOverrides(config: ReviewsmithConfig): ReviewsmithConfig {
        var c = config
        rule?.takeIf { it.isNotEmpty() }?.let { c = c.copy(onlyRules = it) }
        if (noDiff) c = c.copy(scope = c.scope.copy(includeDiff = false))
        if (noInlineSuppression) c = c.copy(suppression = c.suppression.copy(enabled = false))
        maxUnits?.let { c = c.copy(scope = c.scope.copy(maxUnits = it)) }
        if (force) c = c.copy(scope = c.scope.copy(maxUnits = 0))
        reportLevel?.let { c = c.copy(reportLevel = it) }
        return c
    }

    private fun mergeGate(base: GateConfig): GateConfig = base.copy(
        failOn = failOn ?: base.failOn,
        onlyConfidence = onlyConfidence ?: base.onlyConfidence,
        failOnCategory = failOnCategory ?: base.failOnCategory,
        failOnAbandoned = failOnAbandoned || base.failOnAbandoned,
    )

    private fun printDryRun(repoRoot: Path, config: ReviewsmithConfig): Int {
        val effectiveScope = scope ?: config.scope.default
        val files = ScopeResolver().resolve(repoRoot, config, effectiveScope)
        val rules = RuleResolver.resolve(repoRoot, config)
        val units = rules.sumOf { r ->
            if (r.appliesTo.isEmpty()) files.size
            else {
                val matchers = r.appliesTo.map { GlobUtil.matcher(it) }
                files.count { rel -> matchers.any { it(rel) } }
            }
        }
        val low = "$%.2f".format(units * 0.15)
        val high = "$%.2f".format(units * 0.50)
        val cacheState = if (config.cache.enabled) "enabled" else "disabled"
        println(
            "Would analyze ${files.size} file(s), ${rules.size} rule(s) → $units work unit(s).",
        )
        println("Estimated cost: $low–$high (at ~\$0.15–0.50/unit). Cache: $cacheState.")
        println("No agent calls made.")
        return 0
    }

    private fun maybePrintBaselineTip(config: ReviewsmithConfig, repoRoot: Path, findingCount: Int) {
        if (findingCount == 0) return
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
