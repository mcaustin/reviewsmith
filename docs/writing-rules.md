# Writing Reviewsmith rules

A Reviewsmith rule is a Markdown file: optional YAML frontmatter followed by a freeform
body that is sent verbatim as the prompt to the agent.

## Frontmatter fields

Frontmatter is delimited by `---` markers at the top of the file. All fields are
optional.

```yaml
---
name: No blocking calls in coroutines
severity: warning
appliesTo: ["**/*.kt"]
docs: ["docs/concurrency-guide.md"]
tags: ["correctness"]
maxBudgetUsd: 0.75
callTimeoutSeconds: 120
---
```

| Field | Type | Default | Notes |
|---|---|---|---|
| `name` / `description` | string | first `# Heading` in body, or the file stem | `description` is an alias accepted from `.claude/rules` files. |
| `severity` | `INFO` \| `WARNING` \| `ERROR` (case-insensitive) | `WARNING` | Determines gate behavior and console coloring. |
| `appliesTo` / `paths` | list of gitignore-style globs | `[]` (matches nothing — rule never runs) | `paths` is an alias accepted from `.claude/rules` files. Both inline YAML lists (`["**/*.kt"]`) and block-sequence form are accepted. |
| `docs` | list of repo-relative paths | `[]` | Additional files fed to the agent as context alongside the auto-discovered project docs. |
| `tags` | list of strings | `[]` | Used for `gate.failOnCategory` filtering. |
| `maxBudgetUsd` | float | unset (no per-rule cap) | If the agent call would exceed this cost, it is abandoned. The global `callTimeoutSeconds` still applies. An invalid (non-numeric) value is ignored with a warning; no budget cap is applied. |
| `callTimeoutSeconds` | integer | unset (falls back to global `callTimeoutSeconds`, default 300) | A rule-level override for the per-call timeout. An invalid value is ignored with a warning. |

### Glob semantics

`appliesTo` globs use gitignore/ripgrep-style `**` semantics: `**` matches **zero or
more** path segments. This differs from Java's built-in `PathMatcher`, which requires at
least one middle segment. `GlobUtil` expands each `**/` occurrence into both its
zero-segment and one-or-more forms, so `**/*.kt` matches both `Foo.kt` (at the root) and
`src/main/Foo.kt`.

A rule with an empty `appliesTo` list never matches any file and will never run.

## Rule body

The body is everything after the closing `---` of the frontmatter (or the entire file
if there is no frontmatter). It is sent as-is to the agent, so write it as a direct
instruction to a senior engineer.

The shipped rules follow a consistent two-section shape. Use it as a template:

**1. A "Flag issues such as" list** — concrete, enumerated categories of problems the
rule targets. Be specific enough that the agent can decide whether a given hunk is in
scope. From `correctness-safety.md`:

```markdown
Flag issues such as:

- Logic errors, off-by-one, inverted conditions, incorrect operators.
- Null-safety gaps: a deliberately-nullable value (`?`, `orElse(null)`, a nullable
  return) that reaches a use which does not tolerate null. Follow the value to its sink.
- Error-handling gaps: swallowed exceptions, empty catch blocks, resources not closed.
```

**2. A "Discipline — avoid false positives" section** — explicit constraints on what the
agent must *not* flag. This is as important as the affirmative list; without it the
agent over-reports and findings become noise. From `style-convention.md`:

```markdown
**Discipline — avoid false positives:**

- A naming claim requires a concrete counterexample from the project's own source. If
  you cannot cite a sibling declaration that uses a different pattern, do not flag it.
- Do not flag pre-existing issues. Only flag problems introduced or directly exposed
  by the changed code.
```

Close the body by specifying the required output shape:

```markdown
For each real issue, give the file, the line (if known), a one-line summary, and a
rationale citing the specific convention, policy, or sibling pattern it violates.
```

## Where rules load from

Rules are resolved from an ordered `ruleSources` list. The default order (when
`buildUponDefault: true`, which is the default) is:

1. `shipped` — the bundled rules packaged inside the Reviewsmith jar.
2. `.claude/rules` — any `.md` files in that directory (ingested automatically if
   present; useful for rules you already maintain for Claude's coding agent).
3. `reviewsmith/rules` — project-specific rules dropped here.

A rule from a later source **replaces** an earlier rule with the same id (detekt-style
layering). Setting `buildUponDefault: false` in `reviewsmith.yml` drops the shipped
rules entirely; only `.claude/rules` and `reviewsmith/rules` are loaded.

To use an entirely custom source list, set `ruleSources` explicitly:

```yaml
ruleSources: [shipped, my-team/rules]
```

## Per-rule config overrides

Any rule can be tuned or disabled from `reviewsmith.yml` without touching the rule file:

```yaml
rules:
  style-convention:
    enabled: false
  design-impact:
    severity: info
    maxBudgetUsd: 0.75
    callTimeoutSeconds: 90
```

Supported override fields: `enabled` (bool), `severity` (string), `maxBudgetUsd`
(float), `callTimeoutSeconds` (integer). An `enabled: false` override drops the rule
entirely from the resolved set. All other overrides replace the value from the rule
file; unset override fields leave the rule's own value unchanged.

## Testing a new bundled rule with `BundledRuleFixtureTest`

The test harness at
`core/src/test/kotlin/dev/reviewsmith/core/BundledRuleFixtureTest.kt` checks structural
properties only — it does not invoke the agent.

**Step 1 — add fixture files.** Create `positive/` and `negative/` subdirectories under
`core/src/test/resources/reviewsmith/fixtures/<rule-id>/`. Place at least one
representative file in each. For example, for a rule that targets `.kt` and `.sql`
files:

```
core/src/test/resources/reviewsmith/fixtures/my-rule/positive/sample.kt
core/src/test/resources/reviewsmith/fixtures/my-rule/negative/sample.kt
```

The harness does not read or parse these files — they only need to exist at a path that
your rule's `appliesTo` globs would match.

**Step 2 — register in `fixtureSamples`.** Add an entry to the `fixtureSamples` map in
`BundledRuleFixtureTest.kt`:

```kotlin
"my-rule" to listOf(
    "$fixtureBase/my-rule/positive/sample.kt",
    "$fixtureBase/my-rule/negative/sample.kt",
),
```

**Step 3 — register in `RuleResolver`.** Add the rule id to the `bundled` list in
`RuleResolver.kt` and place the rule file at
`core/src/main/resources/reviewsmith/rules/my-rule.md`.

**What the harness asserts:**

- Every id in the `bundled` list is present in the shipped source.
- For every id in `fixtureSamples`, the rule loads successfully and its `appliesTo`
  globs match every listed sample path.
- The two "heavy" rules (`correctness-safety`, `design-impact`) carry the expected
  `callTimeoutSeconds` and `maxBudgetUsd` guardrails.
- The "cheap" rules (`secrets-in-code` and similar) have no guardrails.

The harness deliberately does **not** assert anything about LLM output — it stays
tolerant by design.

Run it with:

```bash
./gradlew :core:test --tests "dev.reviewsmith.core.BundledRuleFixtureTest"
```
