---
name: observability
description: Logging and metrics conventions for barometr — SLF4J placeholders, what belongs at debug, info, warn and error, Micrometer metric naming and tag cardinality, what must never be logged, and making a silent failure visible. Use when adding a log line or metric, deciding a log level, instrumenting a component, catching an exception, or investigating why a failure left no trace.
---

# Logging and metrics

**Apply when** adding a log line, a metric, or a `catch` block.

## Logging

1. **`private val log = LoggerFactory.getLogger(javaClass)`** as the first member.
   Import `org.slf4j.LoggerFactory`; do not fully-qualify it inline.
2. **Placeholders, never interpolation**: `log.info("Queued run for {}", connectorId)`.
   Interpolation formats the string even when the level is off, and destroys the
   structure a log aggregator can key on.
3. **Levels mean specific things here:**
   - `debug` — the detail that helps while working on this component.
   - `info` — a decision or state change someone would want in a postmortem: a
     backfill queued, a connector registered, a source dropped because it was disabled.
   - `warn` — degraded but continuing: a volume anomaly, a refused sub-resource, an
     abandoned job reclaimed, a selector unset.
   - `error` — a deployment or data problem needing a human: no handler for a job type.
     Not "an external source misbehaved", which is expected and retried.
4. **Log the decision, not the traversal.** One line saying what was concluded beats
   twenty saying what was examined.
5. **Every swallowed exception gets a line naming the cause.** `RobotsGate` catches
   `Exception` and returns "no restrictions" silently, so a persistent failure to read
   robots.txt is invisible (review B12).
6. **A dangerous configuration announces itself on every start**, not once in a file —
   the robots exemption logs its stated legal basis each time a client is built.
7. **Include the identifiers a reader will search for** — connector id, source id, job
   id, partition — and nothing else.

## Metrics

8. **Micrometer, dotted names, noun-first**: `jobs.execution`, `jobs.failures`,
   `ingestion.documents.seen`, `ingestion.volume.anomaly`.
9. **Tags are low-cardinality and closed**: connector id, mode, job type. **Never** a
   URL, an external id, a user id or a hash — each distinct value is a new time series.
10. **Prefer an instrumented library to a hand-built one.** `RestClient` gives
    `http.client.requests` and a trace span per fetch; Resilience4j's registry reports
    available permits and waiting threads, so throttling appears on a dashboard instead
    of as unexplained slowness. This is a functional requirement here, not a
    preference — see `library-first`.
11. **Count what would otherwise be invisible.** A source answering HTTP 200 with
    nothing is the most likely failure in this system: nothing throws, no status is
    wrong. That is why a finished run is compared against a baseline and the anomaly is
    both logged and counted (`SourceHealthMonitor`).
12. **Bind optional instrumentation defensively** — `ObjectProvider<MeterRegistry>` and
    `ifAvailable`, so a platform module does not force Actuator onto a consumer that
    only needs to fetch a URL.

## Never

- **Never log a payload, a token, a password hash, an authorization header or a
  secret.** Log its content hash or its id.
- **Never log personal data** — an e-mail address included — outside an explicit audit
  path.
- **Never return internals to a client.** `server.error.include-message: never` and
  `include-stacktrace: never` are deliberate; error responses carry a stable code.
- **Never use a metric where a log line belongs**, or the reverse: a counter cannot
  tell you which document failed, and a log line cannot be graphed.
- **Never leave a `catch` that neither logs, rethrows, nor records a warning on the
  run.**

## Verify

```bash
docker compose up -d && ./gradlew :app:bootRun
curl -s localhost:8080/actuator/health
```

Then check that a deliberate failure produces exactly one log line at the right level,
and that any new metric appears with the tags you expect and no others.
