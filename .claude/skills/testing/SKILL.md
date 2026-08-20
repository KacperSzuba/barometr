---
name: testing
description: Test conventions for barometr — Testcontainers on the production Postgres image instead of H2, plain object construction instead of @SpringBootTest, hand-written fakes instead of a mocking framework, recorded fixtures for connector contract tests, backtick behaviour names, and what must be tested before a change counts as done. Use when writing or changing tests, deciding how to test something, adding a module's test setup, or when a test needs a database, a clock or an HTTP response.
---

# Testing

**Apply when** writing a test, or deciding whether something needs one.

## What gets tested

1. **Security-critical logic is tested first.** Token minting and validation, refresh
   rotation, replay detection, revocation, authorization on operator endpoints.
   `identity-impl` had none of this until the rotation suite was written (review
   C15): rotation, the grace window, replay, revocation, and what the minted token
   claims are all pinned down there now. Keep it that way.
2. **Every guarantee stated in a comment has a test.** If a class claims replay is
   impossible, or that two workers never receive the same job, the claim is a test
   name. `JooqJobQueueTest."concurrent workers never receive the same job"` is the
   model.
3. **Every database constraint that encodes a policy is tested by trying to violate
   it** — `SourceRegistrySeedTest` proves `ck_source_legal_basis_before_enabling`
   actually fires. A constraint nobody has tried to break is a constraint nobody knows
   works.
4. **The context smoke test stays green and stays small.**
   [ApplicationContextTest](app/src/test/kotlin/pl/barometr/ApplicationContextTest.kt)
   is the only test that builds the real context; it caught scheduled work locked by
   an annotation with nothing behind it (review A6). Add to it when a new bean must
   exist at startup — not when a behaviour can be tested without a context.

## How

5. **Real Postgres, never H2.** `pgvector/pgvector:pg16` — the production image —
   migrated by the project's own migrations. H2 accepts `SKIP LOCKED` and ignores it,
   which is precisely the behaviour the queue test exists to prove.
6. **Use the shared database helper**, never a private container:
   [PostgresTestDatabase](shared-testing/src/main/kotlin/pl/barometr/testing/PostgresTestDatabase.kt),
   via `testImplementation(project(":shared:shared-testing"))`. A module that starts
   its own container makes the build wait twice and leaves two setups to keep in step
   (review C18).
7. **Prefer plain construction to `@SpringBootTest`.** Compose the object graph the
   way Spring composes it and test it with no context —
   [RawDocumentArchiverTest](modules/ingestion/src/test/kotlin/pl/barometr/ingestion/internal/RawDocumentArchiverTest.kt)
   builds repository, archiver and sink directly. Context tests are for wiring, and
   wiring is one test, not every test.
8. **Hand-written fakes, not a mocking framework.** `RecordingSink`,
   `FixtureHttpClient`, `RecordingEventPublisher` — each is a dozen lines, reads as a
   specification of the collaborator, and never silently agrees with a signature that
   changed. No mocking library is in the catalog; do not add one.
9. **Connector tests run against recorded responses** from the live source, so a
   *source* changing shape fails the build. A stub written by hand only ever confirms
   what the author already believed —
   [SejmConnectorContractTest](modules/ingestion/src/test/kotlin/pl/barometr/connectors/sejm/SejmConnectorContractTest.kt).
10. **Time is controlled by an injected `Clock`**, not by sleeping and not by
    rewriting rows: `TestClock` in `shared-testing` moves on demand. Backoff, token
    expiry and the refresh grace window are all tested by advancing it (review B11).
11. **Test names are behaviour sentences in backticks**, describing the guarantee:
    `` `identical content is recognised and publishes nothing` ``.
12. **One behaviour per test.** Several assertions about one behaviour are fine;
    several behaviours are several tests.
13. **Assertions carry a message when a bare failure would be cryptic**:
    `assertEquals(1, count, "one row for one piece of content")`.
14. **Test the seam a bug crossed.** A defect found in production gets a test at the
    level where it could have been caught, not at the level where it was noticed.

## Never

- **Never write a test that asserts nothing.** A `println` with a test name on it
  passes forever and tells nobody anything (review C17).
- **Never let a test depend on another test's leftovers.** Clear the tables the test
  owns in `@BeforeEach`.
- **Never assert on a log line** as a substitute for asserting on behaviour.
- **Never hand-write the schema for a test.** If the migrations do not produce it, the
  test is not testing the system.
- **Never skip the test because the code is "just plumbing"** — `ContentHash` looked
  like plumbing and had no tests for months, while every deduplication path in the
  system rested on its equality (review C19).
- **Never mock what you own.** Fake the port, use the real implementation.

## Verify

```bash
docker compose up -d
./gradlew check
```

`check` also runs `ModularityTest` and the boundary rules. Report the output as it is:
a failing test is a result, not an obstacle to work around.
