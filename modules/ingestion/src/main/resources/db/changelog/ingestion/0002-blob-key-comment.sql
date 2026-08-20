--liquibase formatted sql

--changeset kacper:ingestion-0002-blob-key-comment
--comment: Corrects what the first changeset says about blob_key.
-- Corrects what V4001 says about `blob_key`.
--
-- That migration calls the column "deliberately equal to the hex content hash".
-- It is not, and has not been since the store began sharding: the key is
-- `<aa>/<bb>/<hex>`, the first two byte-pairs of the hash as directories, because a
-- flat namespace of millions of objects is painful in every filesystem and in some
-- S3 tooling.
--
-- The point the original comment was reaching for still holds, and is the reason
-- the column exists at all: the key is derived from the content hash alone, so the
-- same PDF reached from two different sources resolves to one object. The column is
-- therefore a denormalisation — `BlobStore.keyOf(content_hash)` computes it — kept
-- so that anything reading this table without the application can find the object.
--
-- Stated as a database comment rather than by editing V4001, which has been applied
-- and whose checksum must not change.
COMMENT ON COLUMN ingestion.raw_document.blob_key IS
    'Object-storage key, derived from content_hash as <aa>/<bb>/<hex>. Denormalised: '
    'BlobStore.keyOf() computes the same value from content_hash alone.';

--rollback COMMENT ON COLUMN ingestion.raw_document.blob_key IS NULL;
