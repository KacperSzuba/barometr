---
name: jooq-persistence
description: Writing jOOQ repositories in barometr — DSLContext injection, mapping records to domain types, upserts with onConflict, returningResult, FOR UPDATE SKIP LOCKED, typed jsonb handling, generated code layout and when to regenerate it. Use when adding or changing a repository, writing a query, handling a jsonb column, or when generated jOOQ classes are missing or out of date.
---

# jOOQ repositories

**Apply when** writing or changing anything that talks to the database.

## Generated code

1. **Generated classes live in the owning context's `internal.jooq` package** and are
   never referenced from another context — the module boundary reaches the database
   too. `barometr.jooq-codegen` sets this up; a module declares only its schema:
   ```kotlin
   jooqCodegen { schema = "ingestion" }
   ```
2. **Regenerate after every schema change**, before writing the query:
   ```bash
   docker compose up -d && ./gradlew :<module>:generateJooq
   ```
   The generator reads a container the migrations have just been applied to, so
   generated code cannot disagree with the schema — as long as it is re-run.
3. **Records only**, no POJOs and no DAOs: a second generated model would compete with
   the domain types the context already has.
4. **Never edit or commit generated sources.**

## Repositories

5. **A repository takes `DSLContext` and holds SQL only** — no events, no blob writes,
   no policy. Everything else about the operation is testable without a database
   because of this split.
6. **`@Repository`, plus `@Transactional(readOnly = true)` for read-only classes**;
   override per method where a write needs it.
7. **Map records to domain types in one private function**, so the column-to-field
   mapping exists in a single place:
   [JooqSourceRegistry.toDefinition](modules/sources/src/main/kotlin/pl/barometr/sources/internal/JooqSourceRegistry.kt)
8. **Return domain types, not records.** A `Record` leaving a repository takes the
   schema with it.
9. **Idempotent insert is `onConflictDoNothing` plus a nullable return**, letting the
   database decide:
   ```kotlin
   val inserted = dsl.insertInto(RAW_DOCUMENT)…onConflictDoNothing().execute()
   return id.takeIf { inserted > 0 }
   ```
   Two callers racing on the same document cannot both win, and no read-then-write gap
   exists to lose. See `RawDocumentRepository.insertIfAbsent`.
10. **Upsert on the natural key** with `onConflict(...).doUpdate()` where the row is a
    position rather than a history — `JooqIngestionCursors.save`.
11. **`returningResult` instead of a second query** when you need what you just wrote.
12. **Claim queries use `FOR UPDATE … SKIP LOCKED` inside the update's subquery**, so
    N workers poll one table without contending and never see the same row twice:
    [JooqJobQueue.claim](platform/src/main/kotlin/pl/barometr/platform/internal/JooqJobQueue.kt)
13. **Timestamps come from an injected `Clock`**, converted once at the boundary
    (`OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)`), never
    `OffsetDateTime.now()` scattered through the statements (review B11).
14. **`jsonb` is decoded with a typed Jackson `TypeReference`**, in one private helper
    per repository:
    ```kotlin
    private fun decodePosition(raw: JSONB): Map<String, String> =
        json.readValue(raw.data(), object : TypeReference<Map<String, String>>() {})
    ```
    An `as Map<String, String>` cast throws far from the cause when a value is not a
    string (review B9).
15. **Build predicates with jOOQ's DSL, including on user input.** `DSL.position`,
    `startsWith`, `substring` — `RawDocumentRepository.countDirectlyUnder` uses them
    precisely so a prefix containing `%` or `_` cannot change the query's meaning.
16. **Prefer a targeted `fetchCount(table, condition)` to loading rows to count them.**

## Never

- **Never write SQL as a string**, including "just this one small query". Typed SQL is
  the reason the schema and the code cannot silently disagree.
- **Never let a repository publish an event, write a blob or make a decision** — that
  is the service's job, and mixing them makes both untestable.
- **Never scan a table in application code to find a row.** Add the query, and the
  index if it needs one (review B10).
- **Never leave `!!` on a column that is nullable in the schema.** On a `NOT NULL`
  column read straight from its own query it is fine; elsewhere it is a latent NPE.
- **Never hold a transaction across a network call.**

## Verify

```bash
./gradlew :<module>:generateJooq :<module>:test
```

A repository change without a Testcontainers test that exercises the new query is not
finished — see `testing`.
