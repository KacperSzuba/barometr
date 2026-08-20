# Backend review — barometr

Date: 2026-08-20 · Scope: the whole Kotlin backend (69 main files, 11 test files,
10 migrations, `build-logic`, `settings.gradle.kts`, `compose.yaml`, configuration).

Every finding below was read in the file, not inferred from a summary. Each one
carries the evidence, what it costs, and the fix. The rules in `.claude/skills/`
are derived from this document; where a skill forbids something, the finding here
is the reason.

## What is good, and stays

Stating this first, because the fixes below only make sense against it.

- **Content addressing as the system's spine.** `ContentHash` is the storage key,
  the idempotency key and the version identity at once, so a connector replay is
  cheap and deduplication across sources needs no coordination.
  (`shared/shared-kernel/.../ContentHash.kt`, `ingestion/.../RawDocumentArchiver.kt`)
- **Invariants pushed into the database.** `ck_source_legal_basis_before_enabling`
  makes "no source goes live without a recorded legal basis" impossible to forget,
  and `SourceRegistrySeedTest` proves the constraint actually fires.
- **`RobotsPolicy` as a sealed type.** An exemption cannot be expressed without a
  written legal basis of real length, and it announces itself in the log on every
  start. This is the best piece of design in the codebase: a policy decision that
  the type system will not let anyone make quietly.
- **The queue.** `FOR UPDATE SKIP LOCKED`, a partial index for claimable rows, a
  partial unique index for dedup, jitter in the backoff, and a concurrency test
  that actually runs four workers against a real Postgres.
- **Testcontainers on the production image** (`pgvector/pgvector:pg16`) with the
  project's own migrations, and codegen generated from a container Flyway has just
  migrated — generated code cannot drift from the schema.
- **Comments that argue.** The house style explains why a decision was taken and
  what breaks otherwise. It is worth keeping, which is exactly why the cases below
  where a comment is no longer true are treated as defects.

---

## Decisions taken in this review

### D-1 · Gradle modules: 20 → ~7, one per service candidate

**Today.** 20 Gradle projects: every domain module split into `-api` and `-impl`,
four `platform/*` projects, two `shared/*`, five connectors and leaves. Five of them
contain no Kotlin at all (`corpus-api`, `corpus-impl`, `legislative-api`,
`legislative-impl`, `platform-persistence`), and two of those still run jOOQ codegen
on every build.

**The problem.** The split does not follow the seam anything would ever be cut on.
Extracting ingestion as a service means taking `ingestion-api`, `ingestion-impl`,
both connectors, `sources-api`, `sources-impl`, `platform-http`, `platform-jobs`,
`platform-storage` and `shared-kernel` — nine projects, a subgraph rather than a
module. The `-api`/`-impl` pair buys compile-time boundary enforcement, which is
real; it costs a doubled project count, a second file to touch for every contract
change, and a build graph where five nodes are empty.

**Decision, applied.** One Gradle module per bounded context — a module is what could
become a service, so everything that context needs to run travels with it. Twenty
projects became nine:

```
:app                     assembly only
:shared                  value types, no framework
:platform                http · jobs · storage · persistence support
:identity
:ingestion               connectors + sources + the archive
:corpus
:legislative
```

Inside each module, `pl.barometr.<context>.api` is the published contract and
`pl.barometr.<context>.internal` is everything else. Enforcement moves from
`barometr.module` to Spring Modulith `@NamedInterface` plus ArchUnit rules that run
in `check` — honestly weaker than a compile error, and the trade accepted knowingly:
the boundary that matters is now the one a service extraction would follow.

**What the move exposed.** `ApplicationModules.verify()` began failing the moment the
Gradle split was gone, with more than a hundred violations — every cross-context use
of a contract. Modulith treats a sub-package as internal unless a named interface
says otherwise, and no `api` package had ever been declared as one. The old layout
had been standing in for a declaration nobody made: Gradle refused the wrong
dependency, so the fact that Modulith had no idea what was public went unnoticed. The
fix is a three-line `package-info.java` per context — Java, because Kotlin has no
package annotations — and `ModularityTest` now applies the internals rule to all
eight packages that have internals rather than to the one that happened to have a
rule written for it.

### D-2 · Flyway → Liquibase (formatted SQL, with contexts)

**Today.** Ten `V<n>__*.sql` files across six modules, ordered by a hand-maintained
ordinal convention (`1xxx` identity, `2xxx` platform, `3xxx` sources, `4xxx`
ingestion, `5xxx` corpus, `6xxx` legislative), with three separate Flyway
configurations reading them.

**The problem.** The convention's own comment says it lets modules "evolve
independently", while the ordinals are precisely the cross-module coupling: `V2000`
must run before the 5xxx and 6xxx schemas that need its extensions, and a new
context has to be granted a free thousand. Seed data (`V3002`, `V3004`) is applied
in every environment including production because a Flyway migration has no notion
of a context.

**Decision.** Liquibase with `--liquibase formatted sql`, so the SQL and its
comments stay exactly as readable as they are now. A master changelog declares the
order explicitly instead of encoding it in filenames; each context owns a changelog;
seed data moves to `context:local,test`. Preconditions replace the assumptions
currently written as prose, and rollback blocks are written where an honest one
exists (and omitted, deliberately, where it does not).

The cost is real — changesets carry an id/author header, and a checksum must be
respected once applied. It is being paid now because the repository has zero commits
and nothing is deployed: ten files today, immovable history later.

### D-3 · One persistence model: jOOQ. JPA/Hibernate removed

**Today.** `identity-impl` is on Spring Data JPA (`UserEntity`, `RefreshTokenEntity`,
two repositories); everything else is on jOOQ. The stated reason is that identity is
small CRUD and "the code already works".

**The problem.** One deployable carries two persistence models, two transaction and
flush semantics, the `kotlin-jpa` plugin, Hibernate's startup cost, and a
`ddl-auto: validate` contract between entities and migrations that has to keep
holding. Four classes and two repositories is a small price to unify.

**Decision.** Rewrite identity's persistence on jOOQ; drop `spring-boot-starter-data-jpa`,
the `kotlin-jpa` plugin and the `spring.jpa` block. The `persistence-choice` skill
records the conditions under which an entity model would be admissible again, and
how it must be written if it ever is.

### D-4 · Naming and file layout become an enforced convention

Rules and worked renames live in the `naming-and-files` skill; the evidence is
findings E27–E32.

---

## Findings

### A · Decisions stated but not implemented

#### A1 — Connector pace is dead configuration
**Evidence.** `ConnectorDescriptor` carries `requestsPerSecond` and
`incrementalInterval`. Grepping every read of those fields in main code: nothing
consumes them. The only descriptor field used at runtime is `supportedModes`, in
`ConnectorRunner.connectorFor`. The rate that actually throttles is built in
`SejmConnectorConfiguration` / `RclConnectorConfiguration` from the properties into
`HttpPolicy`; the cadence that actually schedules is `sources.source.refresh_interval`
read by `IngestionScheduler.dispatchIncremental`.
**Consequence.** One fact declared in three places, two of them inert. Worse,
`RclConnectorWiringTest` asserts `descriptor.requestsPerSecond == 0.05`, so the suite
certifies that configuration is honoured while nothing at runtime reads the value it
checks. `SejmConnector`
hard-codes `2.0` in the descriptor while `SejmProperties` defaults to `2.0`
separately; changing one changes nothing.
**Fix.** The descriptor is the single declaration. `SourceHttpClientFactory` takes
its rate from the descriptor, and the scheduler takes the interval from it unless the
registry row overrides it deliberately — with the override then being the documented
exception, not a coincidence.

#### A2 — A connector's modes are declared twice and can disagree
**Evidence.** `ConnectorDescriptor.supportedModes` versus which of
`IncrementalConnector` / `BackfillConnector` the class implements.
`ConnectorRunner.readFrom` bridges them with `(connector as IncrementalConnector)`.
**Consequence.** A descriptor that over-claims compiles and fails as a
`ClassCastException` inside a job, after a run row has already been opened.
**Fix.** Derive the modes from the type: `buildSet { if (this is IncrementalConnector) ... }`,
or drop `supportedModes` and have the runner match on `is`. Two declarations become
one, and the cast disappears with it.

#### A3 — `RefreshTokenService` is single-instance in a multi-instance system
**Evidence.** The refresh race grace window is an in-memory
`ConcurrentHashMap<UUID, CachedSuccessor>`, with a comment stating "Single-instance
only. Behind more than one replica this has to move to Redis." Meanwhile `JobWorker`
is explicitly designed for many instances and ShedLock is on the classpath for the
same reason. `identity.refresh_tokens` already has `predecessor_id` **and** a unique
index `ux_refresh_tokens_predecessor`, added in V1001 with the comment "lets a racing
refresh find its successor" — the column is written by `issue(...)` and never read.
**Consequence.** The second replica turns a normal parallel refresh from the Next.js
route guard into a detected theft: the family is revoked and the user is logged out.
The infrastructure to prevent it was designed, migrated, indexed, and then not used.
**Fix.** `findByPredecessorId(stored.id)` inside the grace window, and delete the
cache. No Redis, no eviction sweep, and it works on any number of replicas.

#### A4 — The same counters are kept in three places
**Evidence.** A private `class Tally(var seen, var stored)` inside `SejmConnector`
and, duplicated verbatim plus an `attempted` field, inside `RclConnector`;
`RunBoundRawDocumentSink.documentsSeen/documentsStored`; and `FetchResult`'s own
fields. `ConnectorRunner.reportOf` reports the result's numbers on success while
`failureReportOf` reports the sink's.
**Consequence.** Two sources of truth that are authoritative in different branches,
and a duplicated helper class that a third connector will duplicate again.
**Fix.** The sink counts, because the sink is the only thing that knows whether a
payload was stored. `FetchResult` keeps only what the sink cannot know — the next
cursor, `exhausted`, `sourceUnchanged`.

#### A5 — A comment states something the code does not do
**Evidence.** `V4001__raw_document.sql` on `blob_key`: "Deliberately equal to the hex
content hash". `BlobStore.keyOf` returns `"${hex[0..1]}/${hex[2..3]}/$hex"`.
**Consequence.** In a codebase whose method is comment-driven design, a comment that
is false is worse than no comment. The column is also derivable from `content_hash`
in the same row, so it is a second source of truth for a value the archiver computes
anyway — while `RawDocumentArchiverTest` congratulates it as "no second source of
truth".
**Fix.** Correct the comment, and drop the column in favour of deriving the key —
or keep it and say plainly that it is a denormalisation for tooling that reads the
table without the application.

#### A6 — `@SchedulerLock` locks nothing
**Evidence.** `IngestionScheduler.dispatchDueSources` and `JobWorker.reclaimAbandoned`
carry `@SchedulerLock`, and `shedlock-spring` / `shedlock-provider-jdbc-template` are
on the classpath. There was no `@EnableSchedulerLock` anywhere, no `LockProvider`
bean, and no `shedlock` table in any migration. ShedLock honours the annotation only
through the interceptor that `@EnableSchedulerLock` installs.
**Consequence.** The first of the three guards the scheduler's own comment describes
— "`@SchedulerLock` stops two instances dispatching at once" — did not exist. Two
instances would have dispatched the same sources in the same second. The other two
guards (the dedup key, the interval check) would have absorbed most of the damage,
which is exactly why nobody would have noticed.
**Fix.** `BackgroundWorkConfiguration` in `platform-jobs`: `@EnableSchedulerLock`, a
`JdbcTemplateLockProvider` using database time, and `V2200__shedlock.sql`.
**Confirmed by control run.** With the configuration removed, the new
`ApplicationContextTest` fails on the missing `LockProvider`; with it, it passes.

#### Not a finding — scheduling itself
While chasing A6 it looked as though `@Scheduled` was never enabled either: nothing
in this project declares `@EnableScheduling`. A control run with the whole
configuration removed showed scheduled tasks registered regardless — Spring Boot's
autoconfiguration does it on this classpath. The pipeline was running. The annotation
is now declared explicitly anyway, because a load-bearing behaviour that arrives by
autoconfiguration is one an upgrade can take away silently, and
`ApplicationContextTest` asserts the tasks are registered.

### B · Correctness and security

#### B6 — Operator endpoints have no authorization
**Evidence.** `BackfillController` maps `POST /api/v1/ingestion/backfill` and
`GET /api/v1/ingestion/completeness` with no `@PreAuthorize`; the application chain
requires only `authenticated()` for everything outside `/auth/**`, and
`/auth/register` is `permitAll`. `@EnableMethodSecurity` is on and unused everywhere.
**Consequence.** Anyone who registers an account can start a multi-week crawl of a
government registry, from an endpoint whose own documentation calls it "operator
endpoints".
**Fix.** `@PreAuthorize("hasRole('OPERATOR')")` on the controller, a role that
registration cannot grant, and a test that a plain user gets 403.

#### B7 — JSON assembled by string interpolation
**Evidence.** `IngestionScheduler.jobFor` builds
`"""{"sourceId":"${source.id.value}","mode":"${mode.wireName}","partition":"$partition"}"""`.
`IngestionJobHandler` parses it back with an injected Jackson `ObjectMapper`.
**Consequence.** A partition key containing `"` or `\` produces a payload Jackson
cannot read; the job then fails five times and dead-letters. Today's partition keys
are safe by luck (`term10`, an RCL slug), which is exactly the kind of luck that ends
when a source changes its identifiers.
**Fix.** A `data class IngestionJobPayload` serialised by the same mapper that reads
it. The type then also documents the contract that is currently implicit.

#### B8 — Unknown input becomes HTTP 500
**Evidence.** `QueueingBackfillLauncher.launch` and `ArchiveCompletenessAuditor.audit`
use `error("No source registered for connector '$connectorId'")`; both are reached
from `BackfillController` with a caller-supplied `?connector=` parameter. 12 `error(`
calls in main code.
**Consequence.** A typo in a query parameter is reported as a server fault, in a
codebase that built `DomainException` + `ErrorKind` + `ApiExceptionHandler` precisely
so that domain failures map to honest status codes.
**Fix.** `UnknownConnectorException : DomainException(NOT_FOUND, "unknown_connector")`.
Keep `error(...)` for what it means: a state the code believes impossible.

#### B9 — Cursors are decoded through an unchecked cast
**Evidence.** `JooqIngestionCursors` twice: `@Suppress("UNCHECKED_CAST")` over
`json.readValue(it, Map::class.java) as Map<String, String>`.
**Consequence.** A cursor whose position ever holds a nested object or a number
deserialises to values that are not `String`, and the `ClassCastException` surfaces
wherever the map is later read — not here. The decode is also duplicated.
**Fix.** One private `decodePosition(JSONB): Map<String, String>` using Jackson's
`TypeReference`, which fails at the boundary with a message naming the field.

#### B10 — A source is resolved by scanning and comparing strings
**Evidence.** `IngestionJobHandler.handle`:
`sources.enabled().firstOrNull { it.id.value.toString() == payload.sourceId }`.
**Consequence.** Every job execution reads the whole registry and compares
stringified UUIDs, and the payload carries a `String` where a `SourceId` exists.
**Fix.** `SourceRegistry.byId(SourceId)`, and a typed payload (see B7).

#### B11 — Time is not injectable
**Evidence.** 23 direct `Instant.now()` / `OffsetDateTime.now(ZoneOffset.UTC)` calls
in main code — `RawDocumentArchiver`, `AuthService`, `RefreshTokenService`,
`TokenService`, `JooqJobQueue`, `JooqSourceRuns`, `JooqIngestionCursors`,
`RawDocumentRepository`, `IngestionScheduler`. No `Clock` bean anywhere.
`RobotsGate` alone takes a `clock: () -> Instant`, which is a third convention.
**Consequence.** Anything time-dependent is testable only by manipulating the
database: `JooqJobQueueTest` issues `UPDATE job SET run_after = now() - 1 minute` to
observe backoff, and the refresh grace window — a security behaviour — cannot be
tested at all without sleeping.
**Fix.** A single `Clock` bean, injected wherever a timestamp is taken. Tests pass
`Clock.fixed(...)`.

#### B12 — robots.txt cache is per client, not per host
**Evidence.** `SourceHttpClientFactory.create` constructs `RobotsGate(restClient, ...)`
per call, while `HostRateLimiters` is correctly shared through the registry.
`RobotsGate.fetchRules` catches `Exception` and returns `failedFetch(503)` without
logging the cause.
**Consequence.** Each connector re-fetches and re-caches robots.txt for hosts another
connector already knows, and a persistent failure to read robots.txt is invisible.
**Fix.** One `RobotsGate` bean keyed by origin, and a `log.warn` on the failure path.

#### B13 — Two connectors, two failure vocabularies
**Evidence.** RCL has `RclAccessDeniedException` and `RclFetchException`;
`SejmApiClient.read` uses `error("Sejm API failed for $path: ...")` for the same
situation.
**Consequence.** A source outage in Sejm is an `IllegalStateException`,
indistinguishable in logs and handlers from a programming bug.
**Fix.** A shared pair of connector-support exceptions in the ingestion SPI, used by
both — the same module that will hold the shared `Tally` replacement and
`CanonicalJsonPayload`, whose own comment already asks for it.

#### B14 — Mixed concurrency assumptions in one class
**Evidence.** `RunBoundRawDocumentSink` protects `schemaWarnings` with a
`CopyOnWriteArrayList` and increments `documentsSeen` / `documentsStored` as plain
`var`s.
**Consequence.** Either the sink is used from one thread — and the
`CopyOnWriteArrayList` is misleading — or it is not, and the counters are wrong.
**Fix.** Decide, state it in the class comment, and make both members agree.

### C · Tests

#### C15 — `identity-impl` has zero tests
**Evidence.** No `src/test` directory in the module. Overall: 69 main files, 11 test
files.
**Consequence.** JWT minting and validation, BCrypt handling, refresh-token rotation,
theft detection, the grace window and family revocation are the highest-risk logic in
the system and are covered by nothing. The grace-window race in A3 is precisely the
kind of defect a test would have caught.
**Fix.** The first suite written after the refactor: rotation, replay outside the
window, replay inside it, family revocation on logout, audience and issuer
validation, and a disabled user's token being refused.

#### C16 — Nothing ever starts the Spring context
**Evidence.** `@SpringBootTest` appears nowhere. `ModularityTest` uses
`ApplicationModules.of(...)` and ArchUnit, both of which read bytecode.
**Consequence.** A missing bean, an unbound `@ConfigurationProperties`, a Flyway
script that does not apply, or an entity that disagrees with a migration is currently
found by starting the application by hand.
**Fix.** One smoke test that boots the context against Testcontainers and asserts the
connectors and job handlers are registered.

#### C17 — A test that asserts nothing
**Evidence.** `ModularityTest."module structure is printed for review"` — a `println`.
**Fix.** Delete it, or turn it into `modules.writeDocumentation()` as a build step.

#### C18 — Test infrastructure duplicated
**Evidence.** `shared-testing/PostgresTestDatabase` exists and is used by
`ingestion-impl` and `sources-impl`; `JooqJobQueueTest` starts its own container and
runs its own Flyway, and `platform-jobs/build.gradle.kts` declares testcontainers,
junit, flyway-core and flyway-postgresql for that purpose.
**Consequence.** Two containers per build, and two setups that must be kept in step.
**Fix.** `testImplementation(project(":shared:shared-testing"))`, delete the rest.

#### C19 — `shared-kernel` is untested
**Evidence.** No test source set. `ContentHash.parse/of/ofBytes/bytes` and its
`require` guards are pure functions that every deduplication path depends on.
**Fix.** A round-trip test and the rejection cases — cheap, fast, no container.

### D · Build, migrations, repository

#### D20 — Five Gradle modules contain no Kotlin
`corpus-api`, `corpus-impl`, `legislative-api`, `legislative-impl`,
`platform-persistence`. `corpus-impl` and `legislative-impl` still apply
`barometr.jooq-codegen`, so every build migrates a container and generates sources
for schemas no code reads. Resolved by D-1.

#### D21 — Migration order is encoded in filenames
See D-2. The ordinal scheme is the coupling its own comment denies.

#### D22 — Seed data ships to production
`V3002__seed_sejm_source.sql` and `V3004__seed_rcl_source.sql` insert registry rows in
every environment. Resolved by D-2 via `context:local,test` — with the deliberate
question then asked out loud: which of these rows is configuration that production
genuinely needs, and which is developer convenience.

#### D23 — Three migration configurations for one set of files
The application (`classpath:db/migration`, recursive), `MigratedPostgresService` (a
filesystem walk from the repository root, excluding `build`, `.git`, `node_modules`),
and `PostgresTestDatabase` (`classpath:db/migration`). Any drift between them shows
up as generated code that disagrees with the running schema.
**Fix.** One changelog root, referenced by all three.

#### D24 — Zero commits, and 33 MB of ingested blobs inside the tree
`git rev-list --all --count` is 0. `app/.data/blobs/raw` holds 33 MB across 256
shard directories, and `.gitignore` does not mention it — the first `git add .`
commits the archive. The path is a leftover: `application-local.yml` now points the
blob store at `${user.home}/.barometr/blobs`.
**Fix.** `.gitignore` the directory, delete it, then make the first commit.

#### D25 — `README.md` is the untouched Gradle template
It describes an `utils` subproject and `buildSrc`, neither of which exists, and tells
the reader to run `./gradlew run`, which is not how this application starts.

#### D26 — One `@Value` in a `@ConfigurationProperties` codebase
`ArchiveCompletenessAuditor` binds `app.ingestion.completeness-tolerance` with
`@Value`, while `JobWorkerProperties`, `StorageProperties`, `JwtProperties`,
`SejmProperties` and `RclProperties` are all bound as data classes.
**Fix.** An `IngestionProperties` data class; the tolerance is a policy number and
deserves a documented home.

### E · Naming and file layout

#### E27 — 35 files declare more than one top-level type
Worst: `SourceRegistry.kt` (8 — `IngestionMode`, `SourceDefinition`, `SourceRegistry`,
`IngestionCursors`, `RunId`, `RunOutcome`, `RunReport`, `SourceRuns`), `Connector.kt`
(7), `RclChangeRegisterParser.kt` (7), `SourceHttpClient.kt` (6), `RawDocumentSink.kt`
(6), `AuthDtos.kt` (5), `SejmApiClient.kt` (5).
**Consequence.** Finding `RunReport` means grepping, because its file is named after
a sibling. Diffs attribute changes to the wrong subject, and a file's history stops
being the history of a thing.

#### E28 — Bag files named for a category
`Repositories.kt`, `AuthDtos.kt`, `IdentityErrors.kt`, `Ids.kt`,
`IngestionScheduling.kt` (scheduler + job handler), `SejmExternalIds.kt` (also
`SejmPartitions`), `AuthController.kt` (also `MeController`, which serves
`/api/v1/me` and belongs nowhere near `/auth`).

#### E29 — Verbs that name no domain action
`Connector.fetch`, `RawDocumentSink.accept`, `JobHandler.handle`, `ConnectorRunner.run`,
`JobWorker.poll` / `dispatch`, `ArchiveCompleteness.audit`, `RawDocumentSink.warn`.
This is the same list flagged in earlier feedback on this project; the layering was
fixed then and the names were not.

#### E30 — Types named after their mechanism
`RclPages` (builds URLs), `RclDates` (parses two date formats), `Tally`,
`PayloadMediaTypes`.

#### E31 — A layer skipped
`MeController` injects the JPA `UserRepository` and calls `findById` directly,
against this project's own rule that a controller talks to a service and a repository
is reached through one. `UserLookupAdapter` already exists and returns exactly the
snapshot the endpoint needs.

#### E32 — `NewJob.priority: Int`
With `INTERACTIVE = 10`, `DEFAULT_PRIORITY = 100`, `BACKGROUND = 500` as loose
constants, in a codebase that wraps `ConnectorId`, `SourceId`, `JobType` and
`ExternalId` in value classes. `priority = 42` compiles and means nothing.

---

## Refactoring roadmap

Each tranche leaves the build green and is reviewable on its own.

**Status: tranches 1 and 4 are done, along with the parts of 6 and 7 that did not
depend on the rest.** `./gradlew check` passes. Tranches 2 (Liquibase), 3 (JPA
removal) and 5 (naming) remain.

| # | Tranche | Contents | Findings |
|---|---|---|---|
| 1 ✅ | Structure | 20 projects → 9; connectors folded into `ingestion`, `platform/*` into `platform`; `package-info.java` per contract; Modulith + ArchUnit replace `barometr.module` | D-1, D20 |
| 2 | Migrations | Liquibase formatted SQL; master changelog; seeds into contexts; one configuration for app, codegen and tests | D-2, D21, D22, D23 |
| 3 | Persistence | identity onto jOOQ; drop `kotlin-jpa`, `starter-data-jpa`, `spring.jpa` | D-3 |
| 4 ✅ | Correctness | `ConnectorDescriptor` deleted; modes from interfaces; shared `ConnectorRegistry`; sink-owned counters; grace window via the database; typed job payloads; typed cursor decoding; injected `Clock`; `DomainException` on request paths; `@PreAuthorize`; shared `RobotsGate`; shared connector exceptions; ShedLock wired | A1–A6, B6–B14, D26 |
| 5 | Naming | one type per file; the rename table; bag files split | E27–E32 |
| 6 ◐ | Tests | identity suite (24 tests) ✅; context smoke test ✅; `platform-jobs` on `shared-testing` ✅; `println` test replaced ✅; `shared-kernel` tests remain | C15–C19 |
| 7 ✅ | Hygiene | `.gitignore` for `app/.data`; `README.md` rewritten; first commit made | D24, D25 |

Tranche 4 depends on 1–3 only for where the files live, not for what it changes; if
the structural work is deferred, 4 can run first against the current layout.
