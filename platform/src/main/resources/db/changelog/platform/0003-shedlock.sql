--liquibase formatted sql

--changeset kacper:platform-0003-shedlock
--comment: Lock table for work that must happen once across the deployment.
-- ShedLock's table: which scheduled task is being run, by whom, and until when.
--
-- Needed because some scheduled work must happen once across the whole deployment
-- rather than once per instance — dispatching due sources, sweeping abandoned jobs.
-- The worker's own poll is deliberately *not* locked: `SKIP LOCKED` is what keeps
-- pollers apart, and serialising them would throw away the reason the queue scales.
--
-- Column shapes are ShedLock's, not ours; it writes them. `timestamp` without a zone
-- is what the library expects, and the provider is configured with `usingDbTime()`
-- so every instance compares against the database's clock rather than its own.
CREATE TABLE platform.shedlock (
    name       varchar(64)  NOT NULL PRIMARY KEY,
    lock_until timestamp    NOT NULL,
    locked_at  timestamp    NOT NULL,
    locked_by  varchar(255) NOT NULL
);

--rollback DROP TABLE platform.shedlock;
