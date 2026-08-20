-- Asynchronous work queue.
--
-- Postgres rather than a broker: at the volumes this system will see, `SELECT …
-- FOR UPDATE SKIP LOCKED` gives at-least-once delivery, backoff and a dead
-- letter without a second piece of infrastructure to run, and a job can be
-- enqueued in the same transaction as the data that caused it.

CREATE TABLE platform.job (
    id           uuid        PRIMARY KEY,
    type         text        NOT NULL,
    payload      jsonb       NOT NULL DEFAULT '{}'::jsonb,
    status       text        NOT NULL,
    -- Lower runs first. Backfill deliberately sits below live ingestion so a
    -- five-year replay never delays today's documents.
    priority     smallint    NOT NULL DEFAULT 100,
    attempts     int         NOT NULL DEFAULT 0,
    max_attempts int         NOT NULL DEFAULT 5,
    -- Both the schedule and the retry backoff: a failed attempt simply pushes
    -- this forward.
    run_after    timestamptz NOT NULL,
    locked_by    text,
    locked_at    timestamptz,
    last_error   text,
    -- Optional idempotency key for enqueueing.
    dedup_key    text,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,

    CONSTRAINT ck_job_status
        CHECK (status IN ('pending', 'running', 'succeeded', 'failed', 'dead')),
    CONSTRAINT ck_job_attempts
        CHECK (attempts >= 0 AND max_attempts > 0),
    -- A running job must record who holds it, or a crashed worker leaves rows
    -- nobody can reason about.
    CONSTRAINT ck_job_lock_consistent
        CHECK ((status = 'running') = (locked_by IS NOT NULL AND locked_at IS NOT NULL))
);

-- The claim query's index, and the reason this table stays fast as it grows.
-- A queue is mostly history: without the partial predicate every claim would
-- scan millions of finished jobs to find the handful that are pending.
CREATE INDEX ix_job_claimable ON platform.job (priority, run_after)
    WHERE status = 'pending';

-- At most one live job per dedup key, so enqueueing the same work twice is a
-- no-op rather than a duplicate run. Enforced by the database because the check
-- has to hold across concurrent producers.
CREATE UNIQUE INDEX ux_job_dedup ON platform.job (dedup_key)
    WHERE dedup_key IS NOT NULL AND status IN ('pending', 'running');

-- Finds jobs abandoned by a worker that died holding the lock.
CREATE INDEX ix_job_stuck ON platform.job (locked_at)
    WHERE status = 'running';

CREATE INDEX ix_job_dead ON platform.job (updated_at DESC)
    WHERE status = 'dead';
