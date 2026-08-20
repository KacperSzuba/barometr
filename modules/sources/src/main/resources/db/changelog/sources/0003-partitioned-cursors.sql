--liquibase formatted sql

--changeset kacper:sources-0003-partitioned-cursors
--comment: A cursor per partition, not just per mode.
-- Cursors per partition, not just per mode.
--
-- Backfill reads the archive in independently resumable units — one per
-- parliamentary term, one per year — and each needs its own position. A single
-- cursor per mode would force the whole five-year replay to restart from the
-- beginning after any interruption, which is exactly the property backfill exists
-- to avoid.
--
-- Incremental mode keeps using the empty partition, so nothing about it changes.

ALTER TABLE sources.ingestion_cursor
    ADD COLUMN partition text NOT NULL DEFAULT '';

ALTER TABLE sources.ingestion_cursor
    DROP CONSTRAINT ingestion_cursor_pkey;

ALTER TABLE sources.ingestion_cursor
    ADD CONSTRAINT ingestion_cursor_pkey PRIMARY KEY (source_id, mode, partition);

COMMENT ON COLUMN sources.ingestion_cursor.partition IS
    'Resumable unit within a mode: a parliamentary term, a year. Empty for incremental.';

--rollback ALTER TABLE sources.ingestion_cursor DROP CONSTRAINT ingestion_cursor_pkey;
--rollback ALTER TABLE sources.ingestion_cursor DROP COLUMN partition;
--rollback ALTER TABLE sources.ingestion_cursor ADD CONSTRAINT ingestion_cursor_pkey PRIMARY KEY (source_id, mode);
