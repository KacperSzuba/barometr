# barometr

Ingests the Polish legislative process — Sejm, RCL — archives exactly what each
source returned, and derives everything else from that archive.

Kotlin 2.3 · Spring Boot 4 · Postgres 16 with pgvector · jOOQ · Gradle with
convention plugins in an included build.

## Running it

```bash
docker compose up -d          # Postgres with pgvector, on 5432
./gradlew :app:bootRun        # local profile, no further setup needed
```

`SPRING_PROFILES_ACTIVE=prod` requires `DATABASE_URL` and `JWT_SECRET`. Neither has a
production fallback, deliberately: a missing secret must stop the application rather
than sign tokens with a known key.

```bash
./gradlew check                    # tests, module boundaries, modularity
./gradlew :<module>:generateJooq   # after any schema change
```

Tests run against the same Postgres image production uses, migrated by the project's
own migrations. Docker must be running.

## How it is laid out

```
app             assembly: wiring, security chain, error mapping. No domain logic.
shared          value types. No Spring, no persistence, no HTTP.
shared-testing  test harness: a migrated Postgres and a movable clock.
platform        technical capability with no domain meaning: http · jobs · storage
modules/        one bounded context each — identity, sources, ingestion (with the
                connectors that read each source), corpus, legislative
build-logic/    convention plugins, as an included build
```

Nine Gradle projects, one per thing that could become a service. It was twenty, split
`-api`/`-impl` and by technical layer, which meant extracting any one context would
have meant taking nine projects with it.

Each context publishes a contract in `pl.barometr.<context>.api` and keeps
everything else — including all persistence — in `pl.barometr.<context>.internal`.
Contexts talk through published ports or application events, never through each
other's internals, and never through a foreign key across schemas.

The whole thing is one deployable. The boundaries exist so that it does not have to
stay one.

## Working on it

Conventions are in [.claude/skills/](.claude/skills/) — one skill per area, indexed
in [.claude/skills/README.md](.claude/skills/README.md). They are written for an AI
agent and are just as usable as the project's coding standard.

[docs/backend-review.md](docs/backend-review.md) holds the reasoning behind them:
every known defect with its evidence, the four architectural decisions in flight
(module consolidation, Flyway → Liquibase, one persistence model, naming), and the
refactoring roadmap.
