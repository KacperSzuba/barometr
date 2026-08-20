# barometr — backend

Kotlin 2.3 · Spring Boot 4 · Postgres 16 with pgvector · jOOQ · Gradle with
convention plugins in `build-logic`. A modular monolith that ingests Polish
legislative sources, archives exactly what they returned, and derives everything else
from that archive.

## Rules live in skills

Coding standards are in [.claude/skills/](.claude/skills/) — one skill per area, with
an index in [.claude/skills/README.md](.claude/skills/README.md). **Load the skill
that covers what you are touching before writing code**, and run
[change-checklist](.claude/skills/change-checklist/SKILL.md) before calling anything
done.

The reasoning behind them, including every known defect and the refactoring roadmap,
is in [docs/backend-review.md](docs/backend-review.md). Work in progress: removing
JPA from `identity`, and a pass over naming.

## Non-negotiables

1. **Versions only in `gradle/libs.versions.toml`.** A new dependency carries a comment
   saying what in Spring or the JDK does not cover it.
2. **Check what already exists before writing plumbing.** Retry, rate limiting, HTTP,
   parsing, caching are solved on this classpath; hand-writing them here has already
   cost correctness once.
3. **A context's `internal` package is invisible to other contexts.** Cross-boundary
   traffic is a value type over a published port or an application event — never an
   entity, a record or a row. Nothing but `ModularityTest` enforces this, so it is
   load-bearing: a new context needs a line in its `CONTEXTS` list and a
   `package-info.java` naming its `api`.
4. **jOOQ is the persistence model.** No new `@Entity`, no `spring-boot-starter-data-jpa`.
5. **The database holds the invariants.** `CHECK` constraints, unique indexes for
   idempotency, no foreign keys across schemas. Schema changes are Liquibase
   changesets; an applied one is never edited.
6. **Testcontainers on `pgvector/pgvector:pg16`, never H2.** The schema under test is
   the schema the migrations produce.
7. **One public type per file**, and names that state the domain action — not
   `process`, `handle` or `run`.
8. **Comments explain why, and must stay true.** A stale comment is a defect here.
9. **State the same fact once.** Configuration nothing reads is worse than none.
10. **`Clock` is injected.** No `Instant.now()` in a method body.

## Build

```bash
docker compose up -d          # Postgres with pgvector
./gradlew check               # tests, module boundaries, modularity
./gradlew :app:bootRun        # runs on the local profile
./gradlew :<module>:generateJooq   # after any schema change
```

`SPRING_PROFILES_ACTIVE=prod` requires `DATABASE_URL` and `JWT_SECRET`; there are no
production fallbacks, deliberately.
