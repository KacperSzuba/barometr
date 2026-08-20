--liquibase formatted sql

--changeset kacper:sources-0001-source-registry
--comment: The registry of everything the system ingests from.
-- The registry of everything the system ingests from.
--
-- Two jobs beyond bookkeeping: recording the legal basis each source is used
-- on, and holding the operational expectations that make a silently broken
-- source detectable.

CREATE SCHEMA IF NOT EXISTS sources;

CREATE TABLE sources.source (
    id           uuid PRIMARY KEY,
    -- Stable key used by configuration files and logs: 'sejm', 'rcl', 'isap'.
    connector_id text NOT NULL,
    name         text NOT NULL,
    base_url     text NOT NULL,

    -- ——— Legal basis ————————————————————————————————————————————————————
    legal_basis          text,
    license              text,
    attribution_required boolean NOT NULL DEFAULT false,
    commercial_use       boolean,
    review_date          date,

    -- ——— Operational expectations ———————————————————————————————————————
    refresh_interval             interval NOT NULL,
    -- What a healthy run looks like. A source answering HTTP 200 with zero
    -- records is the most common failure in this class of system, and without a
    -- baseline it is indistinguishable from a quiet day.
    expected_min_records_per_run int,

    enabled    boolean     NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT ck_source_connector_id CHECK (connector_id ~ '^[a-z][a-z0-9-]*$'),
    -- The deployment guard, expressed as data rather than code: a source cannot
    -- be switched on until somebody has written down why we are allowed to read
    -- it. Impossible to forget when adding a connector.
    CONSTRAINT ck_source_legal_basis_before_enabling
        CHECK (NOT enabled OR legal_basis IS NOT NULL)
);

CREATE UNIQUE INDEX ux_source_connector ON sources.source (connector_id);

-- Where each connector left off. Incremental and backfill advance separately,
-- so a five-year replay never rewinds live ingestion.
CREATE TABLE sources.ingestion_cursor (
    source_id  uuid        NOT NULL REFERENCES sources.source (id) ON DELETE CASCADE,
    mode       text        NOT NULL,
    -- Shape is the connector's business: changedSince, last external id, ETag.
    -- Typing it here would mean a migration every time one connector learns a
    -- new trick.
    position   jsonb       NOT NULL,
    updated_at timestamptz NOT NULL,

    PRIMARY KEY (source_id, mode),
    CONSTRAINT ck_cursor_mode CHECK (mode IN ('incremental', 'backfill'))
);

-- One row per connector run: the raw material for health monitoring.
CREATE TABLE sources.source_run (
    id               uuid        PRIMARY KEY,
    source_id        uuid        NOT NULL REFERENCES sources.source (id) ON DELETE CASCADE,
    mode             text        NOT NULL,
    started_at       timestamptz NOT NULL,
    finished_at      timestamptz,
    documents_seen   int         NOT NULL DEFAULT 0,
    -- Below `documents_seen` whenever content was already known — the two
    -- together show how much of a run was genuinely new.
    documents_stored int         NOT NULL DEFAULT 0,
    errors           int         NOT NULL DEFAULT 0,
    outcome          text,
    -- Fields the response carried that the connector did not expect, or expected
    -- and did not get. A source changing shape underneath us shows up here
    -- before it shows up as missing data.
    schema_warnings  jsonb,
    failure_reason   text,

    CONSTRAINT ck_source_run_mode CHECK (mode IN ('incremental', 'backfill')),
    CONSTRAINT ck_source_run_outcome
        CHECK (outcome IS NULL OR outcome IN ('succeeded', 'partial', 'failed')),
    CONSTRAINT ck_source_run_counts
        CHECK (documents_seen >= 0 AND documents_stored >= 0 AND errors >= 0)
);

CREATE INDEX ix_source_run_recent ON sources.source_run (source_id, started_at DESC);
-- Feeds the volume-anomaly check, which compares a finished run against the
-- rolling average for its source.
CREATE INDEX ix_source_run_finished ON sources.source_run (source_id, finished_at DESC)
    WHERE finished_at IS NOT NULL;

--rollback DROP TABLE sources.source_run;
--rollback DROP TABLE sources.ingestion_cursor;
--rollback DROP TABLE sources.source;
--rollback DROP SCHEMA sources;
