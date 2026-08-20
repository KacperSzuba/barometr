-- Documents, their versions, and the text extracted from them.
--
-- Character offsets recorded here are what every downstream claim points at:
-- a summary sentence, a stage transition and an act relation all cite a
-- (document_version, char_start, char_end) triple. That is what makes provenance
-- verifiable rather than asserted.

CREATE SCHEMA IF NOT EXISTS corpus;

-- Content-addressed object store index. The hash is the primary key *and* the
-- storage key, so identical bytes are stored once no matter how many sources
-- served them.
CREATE TABLE corpus.blob (
    content_hash bytea       PRIMARY KEY,
    byte_size    bigint      NOT NULL,
    media_type   text        NOT NULL,
    bucket       text        NOT NULL,
    stored_at    timestamptz NOT NULL,

    CONSTRAINT ck_blob_hash_length CHECK (octet_length(content_hash) = 32),
    CONSTRAINT ck_blob_size CHECK (byte_size >= 0)
);

-- The logical document, stable across revisions: "the justification for RCL
-- project UD123" stays one row while its text is rewritten five times.
CREATE TABLE corpus.document (
    id          uuid        PRIMARY KEY,
    -- Cross-schema reference without a foreign key; see the rule in V4001.
    source_id   uuid        NOT NULL,
    external_id text        NOT NULL,
    kind        text        NOT NULL,
    title       text,
    created_at  timestamptz NOT NULL,

    UNIQUE (source_id, external_id)
);

CREATE TABLE corpus.document_version (
    id                  uuid  PRIMARY KEY,
    document_id         uuid  NOT NULL REFERENCES corpus.document (id) ON DELETE CASCADE,
    version_no          int   NOT NULL,
    -- Explicit chain rather than ordering by number alone, so a version fetched
    -- out of order still knows what it superseded.
    previous_version_id uuid  REFERENCES corpus.document_version (id),
    raw_document_id     uuid  NOT NULL,
    content_hash        bytea NOT NULL REFERENCES corpus.blob (content_hash),

    -- Extracted plain text, content-addressed like the original. Every char
    -- offset in the system refers to *this* text, never to the source PDF.
    text_hash    bytea REFERENCES corpus.blob (content_hash),
    text_length  int,
    extracted_at timestamptz,

    published_at timestamptz,
    created_at   timestamptz NOT NULL,

    UNIQUE (document_id, version_no),
    -- "New version detected by hash", enforced rather than implemented: identical
    -- content cannot produce a second version even if two connectors race.
    UNIQUE (document_id, content_hash),
    CONSTRAINT ck_document_version_no CHECK (version_no > 0),
    CONSTRAINT ck_document_version_not_self CHECK (previous_version_id <> id)
);

CREATE INDEX ix_document_version_document
    ON corpus.document_version (document_id, version_no DESC);
CREATE INDEX ix_document_version_raw ON corpus.document_version (raw_document_id);

CREATE TABLE corpus.document_chunk (
    id                  uuid PRIMARY KEY,
    document_version_id uuid NOT NULL
        REFERENCES corpus.document_version (id) ON DELETE CASCADE,
    ordinal             int  NOT NULL,
    char_start          int  NOT NULL,
    char_end            int  NOT NULL,
    content             text NOT NULL,

    UNIQUE (document_version_id, ordinal),
    CONSTRAINT ck_chunk_range CHECK (char_start >= 0 AND char_end > char_start)
);

-- Embeddings kept out of the chunk row on purpose.
--
-- Keyed by model version, two models can coexist while a re-embedding runs:
-- the new vectors are computed alongside the old, a partial index switches
-- reads over, and the previous version is deleted afterwards. Held as a column
-- on `document_chunk` instead, changing models would mean migrating the whole
-- archive in one shot.
CREATE TABLE corpus.chunk_embedding (
    chunk_id      uuid        NOT NULL
        REFERENCES corpus.document_chunk (id) ON DELETE CASCADE,
    model_version text        NOT NULL,
    embedding     vector(1024) NOT NULL,
    computed_at   timestamptz NOT NULL,

    PRIMARY KEY (chunk_id, model_version)
);

-- One partial HNSW index per model version — the pattern, seeded with the model
-- the AI service will start on. A model of different dimensionality needs its
-- own table, since pgvector fixes the width of an indexed column.
CREATE INDEX ix_chunk_embedding_hnsw_mmlw_v1
    ON corpus.chunk_embedding USING hnsw (embedding vector_cosine_ops)
    WHERE model_version = 'mmlw-v1';
