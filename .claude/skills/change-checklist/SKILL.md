---
name: change-checklist
description: The gate to run before calling a change to the barometr backend done — which skill covers what was touched, regenerating jOOQ after a schema change, catalogued dependencies, tests for new guarantees, no fact declared twice, and running ./gradlew check and reporting the result honestly. Use when finishing a change, before saying work is complete, before committing, or when opening a pull request.
---

# Before calling it done

**Apply when** finishing any change to this backend.

This is a gate, not advice. Work through it; a step that was skipped is reported, never
quietly dropped.

## 1 · Did the right skill apply?

| Touched | Skill |
|---|---|
| a new class, package, module, or a cross-context call | `architecture-modules` |
| a file name, type name or method name | `naming-and-files` |
| anything at all in Kotlin | `kotlin-style`, `clean-code` |
| something that sounds like generic plumbing | `library-first` |
| `*.gradle.kts`, the catalog, `build-logic` | `gradle-build` |
| a bean, starter, property, transaction | `spring-boot` |
| a table, column, index, changeset, seed | `database-schema` |
| a query or repository | `jooq-persistence`, `persistence-choice` |
| a connector or anything outbound over HTTP | `source-connectors` |
| a job, handler or scheduled method | `jobs-scheduling` |
| a log line, metric or `catch` | `observability` |
| an endpoint, DTO, error or security rule | `api-security` |
| a test | `testing` |

## 2 · Mechanical checks

- **Schema changed → jOOQ regenerated**: `./gradlew :<module>:generateJooq`, and the
  new columns appear in the queries that need them.
- **Applied changeset edited?** It must not be. Fix forward.
- **New dependency → in [libs.versions.toml](gradle/libs.versions.toml)** with a
  comment saying what in Spring or the JDK does not cover it, and its version verified
  to exist.
- **New module → it contains code**, its boundary rule is in `ModularityTest`, and
  `:app` wires it.
- **New public type → its own file**, named after it.
- **New guarantee stated in a comment → a test asserts it.**
- **Time taken → from an injected `Clock`**, not `Instant.now()`.
- **JSON produced → by Jackson**, not by string interpolation.
- **Caller-caused failure → `DomainException`**, not `error(...)`.
- **New endpoint → authorization decided and tested.**

## 3 · Design checks

- **Is any fact in this diff also declared somewhere else?** A pace, a mode, a counter,
  a default. This is the defect this codebase produces most often — see
  `docs/backend-review.md` A1–A5.
- **Did anything get written that a library on the classpath already does?**
- **Does every method stay in one layer**, in that layer's vocabulary?
- **Is every comment near the change still true?** A comment that has drifted is a
  defect here, not untidiness.
- **Is anything left dead** — a field nobody reads, a parameter nobody passes, a
  suppressed warning?

## 4 · Run it

```bash
docker compose up -d
./gradlew check
```

`check` includes the module boundary checks and `ModularityTest`.

- If it fails, **fix it or report the failure with its output.** Never describe a
  change as working on the strength of it having compiled.
- If a test was not run — no Docker, an environment problem — **say which one and
  why.** An unrun test is not a passing test.
- If part of the change was left out, **say what and why.** Scaling the work down is
  the user's decision.

## 5 · Report

State plainly what changed, what was verified and how, and anything left open. No
hedging on what was proven, and no claiming what was not.
