---
name: jobs-scheduling
description: Background work in barometr — enqueueing to the Postgres job queue with dedup keys and priorities, writing job handlers, scheduled dispatch, ShedLock placement, backoff and dead letters, and typed job payloads. Use when adding a scheduled task or job type, enqueueing work, writing or changing a JobHandler, or when jobs run twice, never run, or dead-letter unexpectedly.
---

# Jobs and scheduling

**Apply when** adding background work, a scheduled method, or a job type.

## The queue

Postgres, not a broker: a job can be enqueued in the same transaction as the data that
caused it, which removes a whole class of bug — a job that fires for a rolled-back row,
or a committed row with no job. At this system's volumes `FOR UPDATE SKIP LOCKED`
delivers at-least-once semantics with nothing extra to operate.

## Rules

1. **Enqueue through `JobQueue.enqueue(NewJob(...))`**, and treat `false` as "already
   in flight", not as an error.
2. **Every job carries a `dedupKey`** that names the work, not the moment:
   `ingestion:<connector>:<mode>[:<partition>]`. The partial unique index makes the
   database the arbiter, so two producers racing cannot both win.
3. **Payloads are serialised by Jackson from a typed class**, by the one component
   that owns the job's wire format — `IngestionRunQueue` owns the type, the dedup key
   and the payload together. Never build JSON by interpolation: a quote in any value
   produces a payload the handler cannot read and a job that dead-letters after five
   attempts (review B7).
4. **Priority is a named level**, not a loose `Int`: interactive above default above
   background. Backfill runs at background so a five-year replay never delays today's
   documents.
5. **Handlers are idempotent**, because delivery is at-least-once. Re-running a job
   must be a no-op at the sink or the repository, not something the handler tries to
   detect.
6. **A handler lets exceptions propagate.** The worker turns that into backoff and, at
   the end, a dead letter. Swallowing an exception to look tidy converts a retryable
   failure into silent data loss.
7. **A missing handler is a deployment error**: fail the job immediately so it reaches
   the dead letter, rather than spinning.
8. **A dead job is kept, never deleted.** Losing the record of what failed is how a
   queue becomes unexplainable.
9. **A job must never enqueue its own successor.** It would have to do so while still
   running, which the dedup key correctly refuses, so the chain stops after one run.
   Derive cadence from observed state instead — `IngestionScheduler` reads
   `lastFinishedAt` and asks "is this due?", which has no such failure mode.
10. **Backoff is exponential, capped, and jittered.** The jitter matters more than the
    curve: without it a hundred jobs that failed against one source retry in the same
    second and reproduce the outage. `JobBackoffPolicy` owns this, separately from the
    repository, because it is a decision rather than persistence.
11. **`@SchedulerLock` on dispatch and on sweeps, never on the worker poll.** Every
    instance *should* poll — `SKIP LOCKED` is what keeps them apart, and locking the
    poll throws away the only reason the queue scales. `JobWorker` documents both
    sides; keep that comment true.
12. **The lock needs something behind it.** `@SchedulerLock` is honoured only through
    the interceptor `@EnableSchedulerLock` installs, and only with a `LockProvider`
    bean and the `platform.shedlock` table. All three are in
    `BackgroundWorkConfiguration`; without them the annotation is decoration and two
    instances dispatch together, silently (review A6). `ApplicationContextTest`
    asserts the provider exists.
13. **Reclaim abandoned work.** A worker that dies holding a lock leaves rows nobody
    can reason about; the reaper returns them after a stated timeout.
14. **Three independent guards against double-firing**, each covering a different case:
    the scheduler lock (two instances dispatching at once), the dedup key (a second job
    while one is pending), and the interval check (a source read more often than it
    wants). Do not remove one because another exists.
15. **Intervals come from configuration with a default in the property placeholder**
    (`\${app.jobs.poll-interval:1000}`), and the properties class documents what the
    number means.
16. **A run that reads what another listener writes waits for it.** Listeners on one
    event run beside each other in no order anybody chose, so a batch that judges an
    item the moment it lands judges it against whichever of them finished first — and
    then marks it judged, which makes the failure silent and permanent. Alerts take
    what has been waiting longer than `app.alerts.settle-delay` for exactly this
    reason (review F-2). Asking the other context "are you done with this one" is not
    the fix: "nothing to say about it" and "not read yet" are the same absence.

## Patterns to copy

- Queue semantics and the claim query:
  [JooqJobQueue](platform/src/main/kotlin/pl/barometr/platform/internal/JooqJobQueue.kt)
- Worker, dispatch and the reaper:
  [JobWorker](platform/src/main/kotlin/pl/barometr/platform/internal/JobWorker.kt)
- Due-based dispatch rather than self-chaining:
  [IngestionScheduler](modules/ingestion/src/main/kotlin/pl/barometr/ingestion/internal/IngestionScheduler.kt)
- One owner for a job's type, dedup key and payload:
  [IngestionRunQueue](modules/ingestion/src/main/kotlin/pl/barometr/ingestion/internal/IngestionRunQueue.kt)
- Enabling the lock:
  [BackgroundWorkConfiguration](platform/src/main/kotlin/pl/barometr/platform/BackgroundWorkConfiguration.kt)

## Never

- **Never call `Thread.sleep` in a handler to wait for something.** Re-enqueue with a
  `runAfter`.
- **Never default a time field to `Instant.now()`.** `NewJob.runAfter` is nullable and
  means "as soon as possible", so the queue's own clock decides — a default that reads
  the producer's clock is untestable and, with a fixed clock in a test, wrong.
- **Never let a handler run longer than the abandoned-after timeout** without chunking
  it; the reaper will hand its work to somebody else.
- **Never put policy in the queue.** It never interprets a payload.
- **Never use `@Scheduled` for work that belongs in the queue** — a scheduled method
  dispatches, it does not do.
- **Never add a broker** for volumes this queue handles; see `library-first` in
  reverse — the simplest thing that already runs in production is Postgres.

## Verify

```bash
./gradlew :platform:platform-jobs:test
```

A new job type needs a test that enqueueing twice with one key yields one job, and
that a failing handler reschedules and eventually dead-letters.
