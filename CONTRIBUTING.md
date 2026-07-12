# Contributing to Reviewsmith

## Prerequisites

**JDK 21** is required to run Gradle. The build targets JVM 17 via toolchains, but
Gradle 8 itself needs JDK 21 as the launcher. If your system default is a newer JDK,
Gradle may refuse to start — export JDK 21 explicitly:

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home
```

Use the Gradle wrapper, not a system-installed Gradle:

```bash
./gradlew build
```

## Build and test

```bash
./gradlew build                  # compile all modules + run all tests
./gradlew :core:test             # run a single module's tests
./gradlew :cli:shadowJar         # build the standalone CLI jar
./gradlew :gradle-plugin:publishToMavenLocal   # publish plugin locally for manual smoke-testing
```

There is no automated CI yet. Run `./gradlew build` locally on JDK 21 before opening a
PR and make sure it passes.

## Module layout

| Module | Responsibility |
|---|---|
| `provider-spi` | `AgentProvider` interface + `Finding` / `AgentRequest` / `AgentResult` / `Model`. Pure — no deps beyond `kotlinx-serialization`. |
| `core` | Engine: `Config`, `Rule`/`RuleParser`, `RuleResolver`, `ScopeResolver`, `GlobUtil`, `DocContextBuilder`, `Prompts`, `Engine`, reporters, `CacheKeyBuilder`, `DiffContext`. No build-tool or provider deps. |
| `provider-claude-code` | `ClaudeCodeProvider` + `ProcessRunner`: shells out to the `claude` CLI. |
| `cli` | Picocli entry point. The shadow jar (`cli-*-all.jar`) is the shipped CLI artifact. |
| `gradle-plugin` | `ReviewsmithPlugin`/`Extension`/`Task`. Bundles the CLI shadow jar as a resource and execs it in a separate JVM (configuration-cache-safe). |

### Boundary rule

`core` must never import Gradle or `provider-claude-code`. When the `claude` CLI is not
on `PATH`, the engine detects absence by class name and exits cleanly — it never fails a
build just because the agent is unavailable. Introducing a compile-time dependency from
`core` into either of those modules would break that guarantee.

## Adding a rule

See [docs/writing-rules.md](docs/writing-rules.md).

## Coding conventions

- **No explanatory or justification comments in code.** That rationale belongs in commit
  messages and PR descriptions, not source files. The sole exception is a single-line
  note for genuinely surprising behavior that a reader would otherwise trip on.
- **Findings must carry `file:line` and a rationale** — both in the agent output
  contract and in any code that constructs `Finding` objects.
- Match the style of surrounding code (indentation, naming, Kotlin idioms).

## PR expectations

- `./gradlew build` must pass on JDK 21 before you open the PR.
- PR description should cover **Why** (motivation) and **What** (what changed). Skip
  acceptance-criteria and test-plan sections — the diff and commit history show what
  changed; the reasoning is what needs to be written down.
