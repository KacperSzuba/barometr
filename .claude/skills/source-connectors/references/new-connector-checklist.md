# Adding a connector

A connector is a leaf: it depends on the ingestion SPI and the HTTP platform, and on
nothing else.

## 1 · Establish that we may read the source

- Read its `robots.txt` and its terms.
- If robots.txt forbids what we need, an exemption requires a **written legal basis** —
  a statutory right of access or permission from the source. It is recorded in
  configuration, logged on every start, and stored on the registry row.
- Decide a pace the source will not notice. Courteous beats fast: the archive is worth
  more than a quick first sync.

## 2 · Registry row

The source is data, not code — one changeset, `context:local,test` unless production
genuinely needs it:

- `connector_id` — lower-kebab-case, the key logs and configuration use.
- `legal_basis` — required before `enabled` can be true; the database enforces it.
- `refresh_interval`, `expected_min_records_per_run` — what a healthy run looks like.
  Without a baseline, a source answering HTTP 200 with nothing is indistinguishable
  from a quiet day.
- `enabled` — false until the legal question is answered.

## 3 · Code

```
<Source>Properties          @ConfigurationProperties, app.connectors.<id>
<Source>Configuration       builds the client and the connector as beans
<Source>ApiClient           URLs, JSON/HTML quirks; returns typed values
<Source>ExternalIds         how an entity is addressed — a contract, not formatting
<Source>Connector           what to read, in what order
```

- All HTTP through `SourceHttpClientFactory.create(HttpPolicy(...))`.
- Implement `IncrementalConnector`, `BackfillConnector`, `AuditableConnector` as the
  source actually supports them — the interfaces are the declaration of what it can do.
- `descriptor()` states the pace and the cadence, and those values must be the ones
  actually used.
- Cursor keys as constants on the connector; advance only after the work completes.
- Backfill in bounded chunks, partitions newest-first.
- One document per entity; canonicalise JSON before it is hashed.
- `declaredVolumes` only where the source states a count independently. A figure
  derived from the list you walked is not authoritative and must say so.

## 4 · Failure behaviour

| Situation | Response |
|---|---|
| sub-resource refused | `SchemaWarning(ACCESS_DENIED)`, run continues |
| index/listing refused | throw — the run must look broken, not idle |
| unexpected field | `SchemaWarning(UNKNOWN_FIELD)` |
| missing expected field | `SchemaWarning(MISSING_FIELD)` |
| source says nothing changed | `sourceUnchanged = true`, not "stored zero" |
| transient HTTP failure | leave it to `SourceHttpClient`'s retry |

## 5 · Tests

Contract tests against **recorded** responses in `src/test/resources/fixtures/<id>/`:

- [ ] first run with no cursor stores the expected entities
- [ ] unchanged source costs the minimum number of requests
- [ ] cursor advances to the expected position
- [ ] an interrupted partition resumes instead of restarting
- [ ] a partition longer than one chunk reports unfinished and advances
- [ ] a refused sub-resource is recorded and skipped
- [ ] a refused index fails the run
- [ ] field order in the response does not change the stored payload
- [ ] configured pace is the pace the connector actually uses

## 6 · Wire it

- `app/build.gradle.kts` — add the project.
- Confirm at startup: `Connectors registered: [...]` lists it.

## Checklist

- [ ] Legal basis established and recorded, or the source stays disabled
- [ ] Registry changeset with pace and expected volume
- [ ] All HTTP through `SourceHttpClient`
- [ ] External-id format defined in one object
- [ ] Cursor keys as constants; advanced only after completion
- [ ] Backfill chunked and resumable
- [ ] Contract tests above, all passing
- [ ] Registered in `:app` and visible in the startup log
