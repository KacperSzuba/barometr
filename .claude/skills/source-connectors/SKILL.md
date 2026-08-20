---
name: source-connectors
description: Writing and changing ingestion connectors in barometr — the Connector SPI, cursors, chunked resumable backfill, going through SourceHttpClient for all outbound HTTP, robots.txt and legal basis, content-addressed idempotency at the sink, canonical payloads, and recording schema warnings. Use when adding a source or connector, changing fetch or backfill logic, touching external-id formats, or adjusting a source's rate limit or polling cadence.
---

# Source connectors

**Apply when** adding or changing a connector, or anything about how a source is read.

## The shape

A connector reads **one** source and hands payloads to a sink. That is all it does: no
database, no blob store, no other context, no scheduling of its own.

```
Connector           decides what to read, in what order            (this class)
<Source>ApiClient   how the source speaks: URLs, JSON, quirks      returns typed values
<Source>ExternalIds how an entity is addressed in the archive      the idempotency key
SourceHttpClient    pace, retries, conditional requests, robots    shared platform
RawDocumentSink     hashing, storage, dedup, the event             shared ingestion
```

The split is what makes a connector readable as a description of a walk rather than as
a parser — see
[SejmConnector](modules/ingestion/src/main/kotlin/pl/barometr/connectors/sejm/SejmConnector.kt).

## Rules

1. **All outbound HTTP goes through `SourceHttpClient`.** Rate limiting, retry with
   jitter, `Retry-After`, conditional requests and the robots/TDM gate live there.
   Twenty connectors with their own retry loops means the one that forgets
   `Retry-After` gets the whole system blocked.
2. **A client returns typed values.** No `JsonNode`, no status codes, no
   `path("x").asString()` outside the client file.
3. **Declare pace once.** A connector exposes `id` and nothing else about itself.
   The rate that throttles comes from the connector's `@ConfigurationProperties` into
   `HttpPolicy`; the cadence that schedules comes from `sources.source.refresh_interval`.
   A descriptor that restated either was read by nobody and asserted on by a test
   (review A1) — if you add a field describing a connector, wire it or leave it out.
4. **Modes come from the interfaces you implement** (`IncrementalConnector`,
   `BackfillConnector`), never from a separate list that can disagree with them, and
   the runner matches with `is` rather than casting (review A2).
5. **Counters belong to the sink.** The sink is the only participant that sees every
   payload *and* knows whether the archive already held it, so `FetchResult` carries
   no counts (review A4). Count something yourself only when it means something the
   sink cannot see — as RCL counts drafts *visited*, including those it was refused.
6. **Idempotency is the sink's**, via content hash and a unique index. A connector may
   safely re-read any range, which is what makes resuming from an approximate cursor
   correct rather than lossy.
7. **One document per entity, never per collection.** A collection stored whole means
   one amended print re-stores all 3205 and pushes the entire archive back through the
   pipeline.
8. **Canonicalise before hashing** — re-serialise with sorted keys at every level, so a
   source that changes field order does not double the archive
   (`CanonicalJsonPayload`).
9. **Cursors are `Map<String, String>` and opaque to everything else.** One source
   paginates by date, another by print number, a third by token; typing this would mean
   a schema change every time a connector learns a trick. Keys are constants on the
   connector (`CURSOR_LAST_PROCEEDING`).
10. **Advance the cursor only after the work it covers is complete.** Moving it earlier
    skips documents permanently.
11. **Backfill reads a bounded chunk and returns.** The cursor becomes durable only
    when the call returns, so reading a whole partition means an interruption discards
    all of it. Partitions are ordered newest-first, so an interrupted replay already
    holds the years anyone will ask about.
12. **A refusal on a sub-resource is a gap; a refusal on the index is a failure.**
    Recording a gap keeps the run going; a source we cannot read at all must look
    broken, never idle with zero documents — that is the failure mode this system is
    least able to notice.
13. **Unexpected shapes become a `SchemaWarning`** (`UNKNOWN_FIELD`, `MISSING_FIELD`,
    `UNEXPECTED_TYPE`, `ACCESS_DENIED`), recorded on the run. A source changing shape
    should surface before it becomes missing data.
14. **`sourceUnchanged` means the source said nothing changed**, not that nothing was
    stored. Collapsing the two makes the volume-anomaly check alert every poll.
15. **External-id formats are a contract.** They are the idempotency key, so changing
    one silently re-ingests every document of that kind. Build them in one object,
    never inline at call sites.
16. **robots.txt is respected unless a written legal basis exists** — `RobotsPolicy` and
    the `sources.source.legal_basis` constraint are two independent locks on the same
    decision, and the exemption announces itself in the log on every start.
17. **Watch the parameter list.** Threading a sink and a counter through every
    private method is the sign of a missing per-run object; dropping the tally took
    `visitCatalog` from seven parameters to six, which is still one too many.

## Never

- **Never fetch outside `SourceHttpClient`**, including "just one probe request".
- **Never invent a second failure vocabulary.** `SourceAccessDeniedException` and
  `SourceFetchException` live in the SPI: a refusal is not worth retrying, a failure
  is, and the runtime should not have to learn two names for each (review B13).
- **Never enable a source without a legal basis** — the database refuses anyway.
- **Never let a connector write to the database or object storage.**
- **Never invent a count for the completeness report.** A figure the source states
  independently is authoritative; one derived from the list you walked is not, and
  reporting them alike makes the report falsely reassuring.
- **Never swallow a fetch failure into a quiet zero.**

## Verify

```bash
./gradlew :modules:connectors:<connector>:test
```

Contract tests must cover: a first run with no cursor, an unchanged source, a resumed
partition, a refused sub-resource, and a refused index.

See also `references/new-connector-checklist.md`.
