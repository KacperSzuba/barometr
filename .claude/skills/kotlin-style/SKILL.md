---
name: kotlin-style
description: Kotlin language conventions for barometr — value classes for ids, data class versus plain class, nullability under -Xjsr305=strict, when !! is allowed, sealed types, init require, injected Clock instead of Instant.now, immutability, scope functions and why this codebase blocks instead of using coroutines. Use when writing or reviewing any Kotlin file in this repo, modelling a new type, or handling nulls from Java or jOOQ APIs.
---

# Kotlin style

**Apply when** writing or reviewing Kotlin in this repository.

## Types

1. **Every identifier is a value class.** `UserId`, `SourceId`, `ConnectorId`,
   `RunId`, `JobType`, `ExternalId`. With this many entity kinds, passing a `SourceId`
   where a `UserId` belongs is otherwise a matter of time, and the compiler catches it
   for nothing.
2. **Wrap constrained strings too**, with the constraint in `init`:
   ```kotlin
   @JvmInline
   value class ConnectorId(val value: String) {
       init { require(value.matches(PATTERN)) { "Connector id must be lower-kebab-case: '$value'" } }
   }
   ```
3. **Never wrap a `ByteArray` in a `data class`.** Generated `equals` compares
   references, so two identical payloads are unequal — silently defeating every
   sameness check built on them. `RawPayload` and `HttpOutcome.Fetched` are plain
   classes for this reason, and `ContentHash` stores hex rather than bytes so that its
   equality means what it says.
4. **Sealed interfaces for states that are not symmetric.** `HttpOutcome`,
   `RobotsPolicy`. A `when` over a sealed type is exhaustive, so adding a case makes
   every consumer a compile error — which is the point.
5. **An enum carries its wire form**, so persistence and JSON cannot drift from the
   name: `IngestionMode(val wireName: String)`, `PayloadKind(val wireName: String)`.
6. **A numeric or string field with a fixed set of meanings is an enum or value
   class**, never a bare `Int` with loose constants — `NewJob.priority: Int` is the
   counter-example (review E32).

## Nullability

7. **`-Xjsr305=strict` is on**, so a JSpecify-annotated Java return is a real Kotlin
   type. Treat a nullable Java result as nullable rather than asserting it away.
8. **`!!` is admissible in exactly one place**: a jOOQ generated column that the
   schema declares `NOT NULL`, read straight after the query that selected it. Anywhere
   else, use `requireNotNull(x) { "…" }` with a message that says what was missing.
9. **`lateinit` is illegal on value-class types** — Kotlin rejects it. Initialise the
   property instead; see the note in `RawDocumentArchiverTest`.

## Time, randomness, identity

10. **Inject `java.time.Clock`. Never call `Instant.now()` or `OffsetDateTime.now()`
    in a method body** — and never as a default argument either, which is where one
    hid longest (`NewJob.runAfter`, review B11). "As soon as possible" is expressed
    as `null` and resolved by whoever owns the clock. Tests use `TestClock`.
11. **Identifiers come from `Ids.next()`** (UUIDv7, time-ordered), generated in the
    application and never by the database, so an aggregate knows its identity before it
    is persisted.
12. **`SecureRandom` for anything a user could guess**; `kotlin.random.Random` only for
    jitter and similar.

## Structure

13. **Constants live in a `private companion object`** at the bottom of the class,
    named for what they mean: `ANOMALY_FRACTION`, `MAX_RETRY_AFTER`, `TOKEN_BYTES`.
14. **Expression bodies for anything that is one expression**, block bodies as soon as
    a `val` appears. Do not chain a whole method into one unreadable expression to
    keep the `=`.
15. **Immutable by default**: `val`, read-only collections, `copy()` on data classes.
    A `var` in a class body needs a reason in a comment.
16. **`require` for arguments, `check` for state, `error` for the impossible.**
    Anything a caller can trigger through the API is a `DomainException` instead.
17. **Scope functions with restraint.** `let` for null handling, `apply` for builder
    configuration. Nested `it` is where readability dies — name the parameter.
18. **Extension functions only where the receiver is genuinely the subject**, and
    private unless they belong to a published contract.

## Concurrency

19. **Blocking, not `suspend`.** Virtual threads are enabled
    (`spring.threads.virtual.enabled: true`), so blocking IO costs nothing worth a
    second concurrency model, and a connector stays readable top to bottom. Adding
    coroutines to a class here needs a stated reason.
20. **Say whether a class is thread-safe** in its comment, and make every member agree
    with the answer.

## Never

- **Never `@Suppress("UNCHECKED_CAST")`.** Over
  `readValue(it, Map::class.java) as Map<String, String>` it hid a real failure mode
  (review B9); Jackson's reified `readValue` types it properly.
- **Never downcast to recover a capability** — `connector as IncrementalConnector`
  (A2). Match with `is`, or model the capability so the cast cannot arise.
- **Never use a platform-default locale or charset**: `String.format(Locale.ROOT, …)`,
  `Charsets.UTF_8` explicitly. A `"%.2f"` on a Polish JVM produced `0,00` and then
  threw on `toDouble()` — the reason `BackfillController` rounds arithmetically.
- **Never let a number round-trip through text** to be formatted.
- **Never catch `Exception` without logging or rethrowing.**

## Verify

```bash
./gradlew compileKotlin compileTestKotlin
grep -rn '!!' --include='*.kt' modules platform shared app | grep '/src/main/'
```

Every surviving `!!` should sit on a jOOQ record read.
