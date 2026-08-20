--liquibase formatted sql

--changeset kacper:ingestion-0001-raw-document
--comment: Everything ever fetched, exactly as it arrived.
-- Everything ever fetched, exactly as it arrived.
--
-- This table is the system's audit trail against its own sources: every derived
-- fact can be traced back to a byte-identical copy of what the source actually
-- returned, which is what makes re-processing possible without re-fetching.

CREATE SCHEMA IF NOT EXISTS ingestion;

CREATE TABLE ingestion.raw_document (
    id           uuid  PRIMARY KEY,
    -- Points at `sources.source` but declares no foreign key.
    --
    -- A cross-schema FK is the same coupling the build forbids between modules,
    -- written in SQL: it welds two schemas into one migration order, stops a
    -- module from being tested or deployed on its own, and would have to be
    -- untangled before either could ever move. Integrity across a module boundary
    -- is the pipeline's job — and the pipeline only ever creates a raw document
    -- for a source it just read from.
    source_id    uuid  NOT NULL,
    -- The source's own identifier: print number, RCL id, ELI, article URL.
    external_id  text  NOT NULL,
    content_hash bytea NOT NULL,
    -- Deliberately equal to the hex content hash: the same PDF reached from two
    -- different sources resolves to one object in storage.
    blob_key     text  NOT NULL,
    payload_kind text  NOT NULL,

    -- Conditional-request state, so an unchanged resource costs one 304 rather
    -- than a full download.
    http_etag          text,
    http_last_modified text,

    fetched_at timestamptz NOT NULL,
    -- Likewise a bare reference to `sources.source_run`.
    run_id     uuid,

    CONSTRAINT ck_raw_document_hash_length CHECK (octet_length(content_hash) = 32),
    CONSTRAINT ck_raw_document_kind CHECK (
        payload_kind IN ('json', 'xml', 'html', 'pdf', 'doc', 'docx', 'csv', 'binary')
    )
);

-- The idempotency key. Re-fetching content already seen is an `ON CONFLICT DO
-- NOTHING` no-op, which is what lets a connector safely replay any range and a
-- backfill resume from an unknown point.
CREATE UNIQUE INDEX ux_raw_document_identity
    ON ingestion.raw_document (source_id, external_id, content_hash);

CREATE INDEX ix_raw_document_source_fetched
    ON ingestion.raw_document (source_id, fetched_at DESC);

-- Finds every source that served identical bytes — deduplication across sources.
CREATE INDEX ix_raw_document_hash ON ingestion.raw_document (content_hash);

-- Deliberately not partitioned. The payload lives in object storage, so a row
-- here is a few hundred bytes; ten million of them are unremarkable for
-- Postgres. Partitioning by `fetched_at` would also force that column into the
-- unique index above and destroy the idempotency guarantee.

--rollback DROP TABLE ingestion.raw_document;
--rollback DROP SCHEMA ingestion;
