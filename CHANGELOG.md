# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **`typescript-safety` default rule** — a ninth bundled rule targeting `**/*.ts` / `**/*.tsx` for TypeScript-specific defects a compiler or ESLint won't reliably catch (floating promises, unsound non-null assertions and `as` casts, exhaustiveness gaps), with positive/negative fixtures.
- **ServiceLoader-based provider discovery** — an `AgentProviderFactory` SPI resolved via `java.util.ServiceLoader`, so alternate agent providers can register without code changes; the CLI resolves through it and falls back to the bundled `claude-code` provider.
- **Contributor docs** — `CONTRIBUTING.md` (build/test on JDK 21, module layout, the core boundary rule) and `docs/writing-rules.md` (rule frontmatter contract + fixture-harness guide).

## [0.1.0] - 2026-07-11

First release published to the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/io.github.mcaustin.reviewsmith).

### Added

- **Walking skeleton** — CLI (Picocli, shadow jar), engine (`core`), `claude` CLI provider, and Gradle plugin wired together end-to-end (Milestone A).
- **8 default rules** with YAML frontmatter, gitignore-style `appliesTo` globs, and fixture-backed tests: `correctness-safety`, `simplification`, `style-convention`, `design-impact`, `evolution-safety`, `secrets-in-code`, `pii-logging`, `backward-compatible-migrations`.
- **Rule sources + config layering** — ordered `ruleSources` list (`shipped`, `.claude/rules`, `reviewsmith/rules`); a later source overrides an earlier rule by id; full `reviewsmith.yml` config with per-rule overrides.
- **Bounded-concurrency orchestration** — one work unit per (rule × matching file), dispatched concurrently up to `maxConcurrency`.
- **Skeptical validator pass** — a second agent call refutes each raw finding, tagging it `CLEAR` or `AMBIGUOUS`; only confident findings gate the build.
- **Baseline / suppress** — `reviewsmith baseline` snapshots current findings to `reviewsmith-baseline.json`; subsequent runs suppress baselined issues so adoption on a large codebase doesn't surface pre-existing noise.
- **Untracked-file scope + `CHANGE_TARGET`** — review untracked files alongside staged and tracked changes; support an explicit base-ref via the `CHANGE_TARGET` env var.
- **Stacked-PR base detection** — consults `gh pr view` to identify the correct diff base when `baseRef` is unset.
- **Agent isolation** — `strict` mode (default) runs the agent via `--safe-mode`, preventing personal `~/.claude` config from leaking into the review; `local` opts in to personal config.
- **Validator chunking** — splits large finding sets across multiple validator calls to stay within context limits.
- **Per-call timeout** — slow `claude` calls are abandoned (not hung) at `callTimeoutSeconds` (default 300 s).
- **Timing and cost instrumentation** — per-(rule × file) wall-clock time and token cost tracked and reported.
- **Per-rule budget cap** — `maxBudgetUsd` frontmatter field caps expensive rules (e.g. `design-impact`); calls exceeding the cap are cut short.
- **Content-addressed response cache** — opt-in; re-running unchanged files costs ~$0; reports cache hit count.
- **Per-rule docs in prompt** — `docs` frontmatter field injects project-specific context files into each rule's prompt.
- **JSON and SARIF reporters** — `--format json` emits findings + run metadata; `--format sarif` emits SARIF 2.1.0 for upload to code-scanning UIs. Machine formats keep stdout clean; human notices go to stderr.
- **Confidence-aware gate** — `gate.failOn` / `gate.failOnCategory` / `--fail-on` / `--only-confidence` flags; exit code `3` when the gate fires.
- **Adoption ergonomics** — `--dry-run`, `--rule` (run a single rule), `reviewsmith init` command, progress output during long runs, Gradle task parity with the CLI flags.
- **Performance** — validation overlapped with the rule phase (not serialized); concurrency raised; per-rule budget caps on heavy rules.
- **Gradle Plugin Portal prep** — clean `plugins {}` DSL, plugin id `io.github.mcaustin.reviewsmith`, published at v0.1.0.
- **Configuration-cache compatibility** — proven via a Gradle TestKit test (P1).

### Changed

- **`suggestedFix` field on `Finding`** — the `Finding` model in `provider-spi` now carries an optional `suggestedFix` string so reporters can surface inline fix suggestions (P2).
- **Console output grouped by rule** — findings in the console reporter are now grouped under their rule heading rather than printed in a flat list (P3).

### Fixed

- Git-runner hang under high concurrency; gate wiring for non-`none` `failOn` values; dead config keys stripped; +26 test additions covering the fixed paths.
- Isolation enforcement, stacked-PR scope edge cases, and validator scaling under large finding sets (from live PR test feedback).

[Unreleased]: https://github.com/mcaustin/reviewsmith/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/mcaustin/reviewsmith/releases/tag/v0.1.0
