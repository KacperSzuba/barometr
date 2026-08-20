---
name: clean-code
description: Layering, comments, duplication and type-design rules for barometr — which layer a piece of logic belongs to (repository, service, client, orchestration), how to write comments that explain why, how to make illegal states unrepresentable, and how to avoid declaring the same fact twice. Use when writing or reviewing any Kotlin in this repo, deciding where logic belongs, writing a comment, or when a class starts doing more than one thing.
---

# Clean code

**Apply when** writing or reviewing any code in this repository.

## Layers

Every method belongs to exactly one layer and touches only that layer's vocabulary.

| Layer | Speaks | Never contains |
|---|---|---|
| **Repository** | SQL / jOOQ, one table family | policy, events, HTTP, blob writes |
| **Client** | one external API, returning *typed* results | domain decisions, persistence |
| **Service** | domain policy, in domain terms | SQL, `JsonNode`, status codes |
| **Orchestration** | the process, step by step | any low-level detail at all |

1. **Decide the layer before writing the method.** A method that opens a run, calls a
   connector, saves a cursor and reports health is orchestration —
   [ConnectorRunner.kt](modules/ingestion/src/main/kotlin/pl/barometr/ingestion/internal/ConnectorRunner.kt)
   reads as the process because every step is somebody else's job.
2. **A client returns typed values.** Callers never see `JsonNode`, a status code or
   `path("number").asString()`.
   [SejmApiClient.kt](modules/ingestion/src/main/kotlin/pl/barometr/connectors/sejm/SejmApiClient.kt)
   keeps all of that inside one file so the connector reads as a description of a walk.
3. **A repository holds SQL and nothing else.**
   [RawDocumentRepository.kt](modules/ingestion/src/main/kotlin/pl/barometr/ingestion/internal/RawDocumentRepository.kt)
   was split out precisely so the rest of ingestion is testable without a database.
4. **Do not add a layer that would only be ceremony.** A context whose repository
   already implements its published port needs no service on top —
   `JooqSourceRegistry` *is* `SourceRegistry`.
5. **Never skip a layer either.** A controller calling a repository is the same
   defect from the other side (review E31).

## One fact, one place

6. **Never declare the same fact in two places.** This is the most common defect
   found in this codebase: connector pace declared in a descriptor, in properties and
   in a database row, with only one of them read (A1); modes declared in
   `supportedModes` and by the interfaces implemented (A2); document counters kept in
   a `Tally`, in the sink and in `FetchResult` (A4). Pick the one that must be true
   and derive the rest.
7. **Configuration that nothing reads is worse than missing configuration**, because
   it reads as a guarantee. If a field has no consumer, delete it or wire it up.
8. **Derive rather than store** when the derivation is cheap and total — `blob_key`
   is `keyOf(content_hash)` and does not need a column (A5).

## Types

9. **Make illegal states unrepresentable.** The model to copy is
   [RobotsPolicy](platform/src/main/kotlin/pl/barometr/http/SourceHttpClient.kt):
   a sealed type where the permissive case cannot be constructed without a written
   legal basis of real length. A boolean plus a comment would have allowed the
   dangerous state to exist quietly.
10. **Put invariants in `init { require(...) }`**, so a bad value cannot travel.
    `ConnectorId`, `ExternalId`, `JobType`, `ConnectorDescriptor` all do this.
11. **Distinguish failures that mean different things.** `SinkOutcome.STORED` vs
    `ALREADY_KNOWN`, `HttpOutcome.Refused` vs `Failed`, authoritative vs
    non-authoritative counts in `CompletenessReport`. Collapsing two outcomes into a
    boolean is how a real signal becomes invisible.
12. **`error(...)` means "a state I believe impossible".** For anything a caller can
    cause, throw a `DomainException` with an `ErrorKind` and a stable code — see
    `api-security`.

## Comments

13. **A comment explains why, and what breaks otherwise.** Not what the line does.
    The house style here is good and worth keeping: read
    [0002-job-queue.sql](platform/src/main/resources/db/changelog/platform/0002-job-queue.sql)
    or [JobWorker.kt](platform/src/main/kotlin/pl/barometr/platform/internal/JobWorker.kt).
14. **A comment must stay true.** In a codebase that argues its decisions in prose, a
    stale comment is a defect, not untidiness — V4001 still claims `blob_key` equals
    the hex hash when it has not for some time (A5). Changing code means re-reading
    the comment above it.
15. **Comment the absence of something when the absence is a decision.** `JobWorker`
    explains why it has no `@SchedulerLock`; `AuthService.refresh` explains why it is
    not `@Transactional`. Those are the comments that save the next reader from
    "fixing" it.
16. **Do not narrate the obvious.** No `// increment the counter`.

## Never

- **Never leave dead code, dead configuration or a dead field.** Delete it; git
  remembers.
- **Never mix levels of abstraction in one method.** HTTP calls, JSON navigation,
  string building, counters and SQL in one body was the defect that produced this
  rule in the first place.
- **Never swallow an exception without recording it**: `RobotsGate` used to catch
  `Exception` and answer "no restrictions" in silence (B12).
- **Never let a class carry mutable state whose thread-safety is undecided**:
  `RunBoundRawDocumentSink` guarded one member and not the others (B14).

## Verify

Read the diff and ask, per method: which layer is this, and does every line speak
that layer's vocabulary? Then: is any fact in this diff also stated somewhere else?

```bash
./gradlew check
```
