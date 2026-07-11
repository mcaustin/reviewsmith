# Reviewsmith

**AI-agent code review that reasons about intent — a linter for what static analysis can't express.**

Where Checkstyle, detekt, and ESLint match AST patterns, Reviewsmith runs natural-language
**rules** (prompts) through a local AI agent that reads the diff, follows values across
files, consults your project docs, and reports issues about *intent* — the bugs and design
smells a pattern matcher structurally cannot see. It's invoked like any other verification
task (`reviewsmith`, or `./gradlew reviewsmith`), ships an opinionated default rule set, and
is fully customizable in the detekt/Spotless tradition.

> **Status: experimental (v0.0.1).** The engine is feature-complete and has been run
> end-to-end against real repositories, but APIs, config, and distribution are still moving.
> Reviewsmith drives the [`claude`](https://docs.anthropic.com/en/docs/claude-code) CLI
> under the hood, so it needs that CLI installed and authenticated.

---

## Why Reviewsmith

An agent-based reviewer is only useful if its findings are **trustworthy**. Freeform
"review this PR" prompts produce noise that teams learn to ignore. Reviewsmith's
differentiator is a strong, opinionated default pipeline that gives a quality floor out of
the box:

- **A skeptical validator pass.** Every raw finding is re-examined by a second agent that
  tries to *refute* it, tagging each `CLEAR` / `AMBIGUOUS`. Only confident findings gate.
- **Scope discipline.** Rules review only the changed files (by default), and are told not
  to flag pre-existing issues the diff didn't introduce.
- **Evidence grounding.** Findings carry a `file:line` and a rationale you can check.
- **Reasons about intent, not just shape.** It follows a nullable value to its sink, spots a
  DLQ-defeating `throw`, notices a migration that isn't backward-compatible — things no
  AST rule encodes.

Like detekt's `buildUponDefaultConfig`, that profile is **fully overridable**: a repo's
`reviewsmith.yml` can retune or switch off any stage, and teams drop in their own markdown
rule prompts.

---

## Requirements

- **JDK 21** to run (the build targets JVM 17 via toolchains; JDK 21 runs Gradle).
- The **[`claude` CLI](https://docs.anthropic.com/en/docs/claude-code)** on your `PATH`,
  authenticated (OAuth login or API key). Reviewsmith shells out to it.
  - If `claude` isn't found, Reviewsmith prints a friendly notice and **exits 0** — it never
    fails your build just because the agent is unavailable.
- A **git repository** — scope resolution is diff-based.

---

## Quick start (CLI)

Build the self-contained CLI jar and run it against any repo:

```bash
export JAVA_HOME=/path/to/jdk-21          # Gradle needs JDK 21
git clone https://github.com/mcaustin/reviewsmith.git
cd reviewsmith
./gradlew :cli:shadowJar                  # builds cli/build/libs/cli-0.0.1-SNAPSHOT-all.jar

# review the changed files in some repo
java -jar cli/build/libs/cli-0.0.1-SNAPSHOT-all.jar --root /path/to/your/repo
```

By default it reviews **changed files** (working tree + staged + untracked, diffed against
the detected base branch). Use `--scope full` to review the whole tree.

### Example run

```console
$ java -jar cli-0.0.1-SNAPSHOT-all.jar --root ~/code/orders --model claude-opus-4-8
Reviewsmith: analyzing changed files in /Users/you/code/orders ...

Reviewsmith — reviewed 3 file(s)

ERROR src/main/kotlin/orders/RetryConfig.kt:42 [clear]
  `delay * 2` can overflow to a negative Long and bypass the max-delay cap.  (correctness-safety)
  The doubled value is compared against maxDelay *after* the multiply; on overflow it wraps
  negative, so coerceAtMost never triggers and the caller sleeps for a negative duration.

WARNING src/main/kotlin/orders/RetryConfig.kt:39 [clear]
  The `for (i in 0 until n) { ... }` loop ignores `i`; use `repeat(n)`.  (simplification)

3 finding(s): 1 error, 2 warning, 0 info

Reviewsmith: run cost $0.94
```

The `[clear]` / `[ambiguous]` tag is the validator's confidence. Machine formats
(`--format json|sarif`) keep **stdout pure** — human notices go to stderr.

---

## Quick start (Gradle plugin)

The plugin registers a `reviewsmith` task that runs the bundled CLI in a separate JVM
(configuration-cache-safe). It is **advisory** and **not** wired into `check` by default.

Reviewsmith is published to the [Gradle Plugin Portal](https://plugins.gradle.org), so any
build that resolves plugins from the Portal (directly, or through a mirror that proxies it)
can apply it with the standard `plugins { }` DSL — no `settings.gradle.kts` change:

```kotlin
// build.gradle.kts
plugins {
    id("io.github.mcaustin.reviewsmith") version "0.1.0"
}

reviewsmith {
    scope.set("changed")           // "changed" (default) | "full"
    model.set("claude-opus-4-8")   // optional; omit to use the claude CLI default
}
```

Then:

```bash
./gradlew reviewsmith
```

---

## The default rules

Reviewsmith ships eight rules — the five review "lenses" plus three safety checks. Each is a
markdown prompt with YAML frontmatter (severity + file globs). Run `reviewsmith --list-rules`
to print the resolved set for a repo without calling the agent.

| Rule | Severity | Catches |
|---|---|---|
| `correctness-safety` | error | Logic errors, null-safety gaps, swallowed exceptions, races, injection. |
| `secrets-in-code` | error | Hardcoded credentials, tokens, keys committed to source. |
| `pii-logging` | error | Personal data leaking into logs / error messages. |
| `backward-compatible-migrations` | error | Schema/proto migrations that break rolling deploys. |
| `design-impact` | warning | Cross-cutting design smells; change ripple beyond the diff. |
| `evolution-safety` | warning | Changes that make the code harder to evolve safely. |
| `simplification` | warning | Over-engineering; simpler equivalents. |
| `style-convention` | info | Convention/idiom drift from the surrounding code. |

---

## Configuration

All optional — the defaults are sensible. Drop a `reviewsmith.yml` at the repo root:

```yaml
scope:
  default: changed               # "changed" | "full"
  # baseRef: origin/main         # force a diff base (auto-detected if unset — handles stacked PRs)
  detectBase: true               # consult `gh pr view` for the PR base when baseRef unset
  include: ["**/*.kt", "**/*.java", "**/*.ts", "**/*.tsx"]

docs:      { autoDiscover: true, maxDocs: 8 }   # feed project docs to the agent as context
validator: { enabled: true, timeoutSeconds: 600, chunkSize: 20 }
agent:     { isolation: strict }  # strict = hermetic (default) | local = apply your ~/.claude config
maxConcurrency: 6                 # tunes wall-clock (rate-limit risk), not cost/quality
callTimeoutSeconds: 300           # per-agent-call timeout; a slow call is abandoned, not hung

cache: { enabled: false, dir: .reviewsmith/cache, maxEntries: 500 }  # opt-in; requires --model

gate:                             # opt-in CI gate; default failOn:none => always exit 0
  failOn: none                    # none | warning | error (validator-CLEAR, non-baselined only)
  failOnCategory: []              # e.g. [safety] — gate findings whose rule carries that tag

rules:                            # per-rule overrides
  style-convention: { enabled: false }
  design-impact:    { severity: info, maxBudgetUsd: 0.75 }
```

**Isolation.** `strict` (the default) runs the agent hermetically via `--safe-mode`,
disabling your personal `~/.claude/CLAUDE.md`, skills, and plugins so a reviewer's private
conventions don't leak onto a repo that never adopted them. Repo conventions still reach the
agent via auto-discovered docs. Use `local` to intentionally apply your own Claude config.

### Custom rules

Rules load from an ordered `ruleSources` list (default `[shipped, .claude/rules,
reviewsmith/rules]`); a later source overrides an earlier one by rule id. To add your own,
drop a markdown file in `reviewsmith/rules/` (or reuse existing `.claude/rules/*.md`, which
are auto-ingested):

```markdown
---
name: No blocking calls in coroutines
severity: warning
appliesTo: ["**/*.kt"]
tags: ["correctness"]
---

Flag any blocking I/O (Thread.sleep, blocking JDBC, synchronous HTTP) invoked from inside a
suspend function or a coroutine builder without a Dispatchers.IO context switch. ...
```

---

## CLI reference

```
reviewsmith [options]
reviewsmith baseline            # snapshot current findings to suppress known issues

Options:
  --scope changed|full          Review changed files (default) or the whole tree.
  --root <dir>                  Repository root (default: current directory).
  --model <id>                  Model id passed to the claude CLI (e.g. claude-opus-4-8).
  --format console|json|sarif   Output format. json/sarif print machine-clean to stdout.
  --isolation strict|local      Override agent.isolation (default strict/hermetic).
  --list-rules                  Print the resolved rules and exit (no agent calls).
  --no-cache                    Bypass cache reads and writes for this run.
  --refresh                     Bypass cache reads; re-warm the cache with fresh results.
  --no-color                    Disable ANSI color.
  -h, --help / -V, --version
```

**Exit codes:** `0` = ok / advisory / gate-passed / agent-unavailable;
`3` = the confidence-aware gate triggered; `1`/`2` = CLI usage error.

### Baseline (drop-in adoption)

`reviewsmith baseline` snapshots today's findings to `reviewsmith-baseline.json`. Later runs
suppress the baselined findings and surface only *new* issues — so you can adopt Reviewsmith
on a large existing codebase without drowning in pre-existing warnings.

---

## CI integration

Reviewsmith is advisory by default (always exit 0). To make it gate a build, opt in:

```yaml
# reviewsmith.yml
gate:
  failOn: error          # fail (exit 3) on validator-CLEAR, non-baselined error findings
```

For code-scanning UIs, emit SARIF 2.1.0 and upload it:

```bash
reviewsmith --format sarif > reviewsmith.sarif
# then e.g. github/codeql-action/upload-sarif to annotate the PR
```

`--format json` emits findings plus run metadata (cost, model) and the resolved config, for
custom tooling.

---

## How it works

```
git diff scope  ─►  one work unit per (rule × matching file)  ─►  skeptical validator  ─►  report
                    (bounded-concurrent agent calls)              (refutes each finding)
```

1. **Scope** resolves the changed files (tracked + staged + untracked), diffed against the
   auto-detected base branch.
2. Each **(rule × file)** pair becomes a work unit dispatched to the `claude` agent
   concurrently (bounded by `maxConcurrency`). The agent reads the file, follows values into
   surrounding code, and consults auto-discovered project docs.
3. A **validator** pass re-examines every raw finding and tries to refute it, tagging
   confidence (`CLEAR` / `AMBIGUOUS`).
4. The **reporter** renders console / JSON / SARIF; the optional **gate** decides the exit
   code.

### Cost & latency

Reviewsmith runs real agent calls, so it costs real money and time:

- **~$0.15–0.50 per (rule × file)**, **~$2–4 per file** for a full 8-rule pass.
- Per-call latency: p50 ~33s, p90 ~115s. A slow call is abandoned at `callTimeoutSeconds`
  (default 300s) rather than hanging the run.
- The **response cache** (opt-in) makes re-running an unchanged file cost ~$0 — a run reports
  `N cache hit(s)`.
- `maxConcurrency` tunes wall-clock time (and rate-limit risk), not cost or quality.

Less time per call yields worse reviews (the deep cross-file passes find the real bugs), so
per-unit budgets are deliberately generous. Scope to `changed` for day-to-day use.

---

## Building from source

```bash
export JAVA_HOME=/path/to/jdk-21
./gradlew build                              # compile all modules + run tests
./gradlew :cli:shadowJar                     # build the standalone CLI jar
./gradlew :gradle-plugin:publishToMavenLocal # publish the plugin locally for testing
```

### Module layout

| Module | Responsibility |
|---|---|
| `provider-spi` | `AgentProvider` interface + `Finding` / `AgentRequest` / `AgentResult` model. |
| `core` | The engine — scope, rules, config, prompts, concurrent orchestration, validator, reporters. No build-tool or provider deps. |
| `provider-claude-code` | The `claude` CLI provider (process runner, JSON parsing). |
| `cli` | Picocli entry point; the shadow jar is the shipped artifact. |
| `gradle-plugin` | The `dev.reviewsmith` plugin; bundles and execs the CLI jar. |

---

## License

[Apache-2.0](LICENSE).
