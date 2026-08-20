--liquibase formatted sql
--
-- <What this file is for, in one sentence.>
--
-- <Why it exists and what would go wrong without it. This is the part that is worth
-- writing: the next reader can see what the DDL does, not why it is shaped this way.>

--changeset kacper:<context>-0001-<what>
--comment: <one line, shown in the Liquibase log>
CREATE SCHEMA IF NOT EXISTS <context>;

CREATE TABLE <context>.<table> (
    id         uuid        PRIMARY KEY,
    -- Cross-context reference without a foreign key: an FK here would weld two
    -- schemas into one migration order. Integrity is the pipeline's job.
    other_id   uuid        NOT NULL,
    -- Always timestamptz, always UTC.
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    -- Invariants belong in the database, where no code path can forget them.
    CONSTRAINT ck_<table>_<rule> CHECK (<predicate>)
);
--rollback DROP TABLE <context>.<table>;

--changeset kacper:<context>-0002-<what>
--comment: The index the hot query needs, and the reason it is partial.
-- A partial predicate keeps this fast as the table grows into history.
CREATE INDEX ix_<table>_<purpose> ON <context>.<table> (col_a, col_b)
    WHERE status = 'pending';
--rollback DROP INDEX <context>.ix_<table>_<purpose>;

-- The idempotency key. Uniqueness is how a duplicate is prevented, rather than a
-- read-then-write check that two callers can both pass.
--changeset kacper:<context>-0003-<what>
CREATE UNIQUE INDEX ux_<table>_identity ON <context>.<table> (col_a, col_b, col_c);
--rollback DROP INDEX <context>.ux_<table>_identity;

-- Seed data is never a plain changeset: it carries a context, so production does not
-- receive developer convenience by default.
--changeset kacper:<context>-0004-seed-<what> context:local,test
INSERT INTO <context>.<table> (id, other_id, created_at, updated_at)
VALUES ('…', '…', now(), now());
--rollback DELETE FROM <context>.<table> WHERE id = '…';

-- A guard whose assumption is load-bearing fails; one that is merely idempotent marks
-- itself as run.
--changeset kacper:<context>-0005-<what>
--precondition-sql-check expectedResult:1 SELECT count(*) FROM pg_extension WHERE extname = 'vector'
--preconditions onFail:HALT onError:HALT
ALTER TABLE <context>.<table> ADD COLUMN embedding vector(1536);
--rollback ALTER TABLE <context>.<table> DROP COLUMN embedding;
