# Agent skills for the barometr backend

Rules an AI agent must follow when working in this repository. They are derived from
[docs/backend-review.md](../../docs/backend-review.md) — where a rule forbids
something, that document holds the evidence and the reasoning.

## Format

Each skill is a directory with a `SKILL.md`:

```markdown
---
name: <kebab-case, matching the directory>
description: <what it covers and when it applies — the only part loaded until the skill fires>
---
```

The body is ≤200 lines and always in the same order: **Apply when · Rules · Patterns to
copy · Never · Verify**. Longer material (checklists, templates) goes in
`references/` and is read on demand.

## How they are used

- **Claude Code** discovers `.claude/skills/` in this repository automatically.
- **Another agent, or an MCP host** — point it at this directory. The format is plain
  markdown plus frontmatter; nothing here depends on a particular runtime.
- **A human** can read them as the project's coding standard, which is what they are.

## The skills

| Skill | Covers |
|---|---|
| [architecture-modules](architecture-modules/SKILL.md) | module and context boundaries, events, ports, what may depend on what |
| [naming-and-files](naming-and-files/SKILL.md) | one type per file, names that state the domain action, the rename table |
| [clean-code](clean-code/SKILL.md) | layering, comments that stay true, one fact in one place, illegal states |
| [library-first](library-first/SKILL.md) | check the classpath before writing plumbing; the procedure, not the slogan |
| [kotlin-style](kotlin-style/SKILL.md) | value classes, nullability, sealed types, injected `Clock`, concurrency |
| [gradle-build](gradle-build/SKILL.md) | version catalog, convention plugins, `api` vs `implementation`, codegen |
| [spring-boot](spring-boot/SKILL.md) | Boot 4 starters and Jackson 3, DI, `@ConfigurationProperties`, transactions |
| [database-schema](database-schema/SKILL.md) | Liquibase changesets and contexts; Postgres schema design |
| [jooq-persistence](jooq-persistence/SKILL.md) | repositories, upserts, `SKIP LOCKED`, typed `jsonb`, codegen |
| [persistence-choice](persistence-choice/SKILL.md) | jOOQ is the model; when JPA would be admissible and how it must be written |
| [testing](testing/SKILL.md) | Testcontainers over H2, fakes over mocks, contract tests, what must be tested |
| [source-connectors](source-connectors/SKILL.md) | the connector SPI, cursors, backfill, robots and legal basis |
| [jobs-scheduling](jobs-scheduling/SKILL.md) | the Postgres queue, dedup keys, ShedLock placement, backoff |
| [observability](observability/SKILL.md) | log levels and placeholders, metric naming and cardinality |
| [api-security](api-security/SKILL.md) | controllers and DTOs, error mapping, authorization, JWT and secrets |
| [change-checklist](change-checklist/SKILL.md) | the gate to run before calling a change done |

## Keeping them true

A skill that contradicts the code is worse than no skill. When a convention changes,
the skill changes in the same commit — and when a skill cites a file, that file must
exist:

```bash
grep -ohE '\((app|modules|platform|shared|build-logic|gradle|docs)/[^):]+' -r .claude/skills \
  | tr -d '(' | sort -u | while read -r p; do [ -e "$p" ] || echo "missing: $p"; done
```

Some rules describe the **target** state rather than today's code — the module
consolidation, Liquibase, and the removal of JPA are in progress (see the review's
roadmap). Those rules say so where they appear.
