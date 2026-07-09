---
name: Evolution Safety
severity: warning
appliesTo: ["**/*.kt", "**/*.java", "**/*.ts", "**/*.tsx"]
tags: ["design"]
---

You are a senior engineer reviewing the changed code for **evolution-safety**: places
where the change relies on an assumption that the type system or the tests do **not**
enforce, such that a future change could silently make it wrong. This lens flags fragility
even when nothing is broken today — that is its purpose.

For each hunk, ask: *what invariant does this rely on, and if a future engineer changes
the related enum / sealed type / DTO / registry / schema, will the compiler or a test stop
them?* Flag patterns such as:

- A `Map`/`Set`/`List` of enum values populated by hand-listing a **subset** — a new enum
  value is silently absent. Prefer deriving from `entries` or a property on the enum.
- A `when`/`switch` over an enum or sealed type with an `else`/`default` that silently
  swallows new cases. Prefer exhaustive matching so the compiler flags new variants.
- A hand-maintained `Type -> Handler` registry with a silent default for unknown types.
- String-keyed dispatch over values that are actually typed — renames miss the string side.
- A new required field on a shared DTO added as nullable/defaulted with no round-trip
  test, while a sibling write path constructs the same DTO and now diverges.
- Two collections or dispatch tables that must stay in sync, with no test pinning parity.
- A consumer depending on an unencoded producer invariant ("always sorted", "never empty").

**Discipline:** name the specific assumption and the concrete future change that would
break it silently. Do not flag code merely because it *could* be written differently —
only when a realistic future edit would introduce a silent bug. Only flag issues caused by
or related to the current change.

For each real issue, give the file, the line (if known), a one-line summary, and the
rationale (the assumption + the future change that breaks it).
