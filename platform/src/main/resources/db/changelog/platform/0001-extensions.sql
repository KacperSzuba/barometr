--liquibase formatted sql

--changeset kacper:platform-0001-extensions
--comment: Extensions and schema the domain model rests on.
-- Extensions the domain schema depends on. Runs before any context's tables,
-- because db/changelog/master.yaml lists platform first and every other schema is
-- built on what this creates.

-- Embeddings live beside the relational data instead of in a separate vector
-- database — one system to operate, and joins between a chunk and its vector
-- stay ordinary SQL.
CREATE EXTENSION IF NOT EXISTS vector;

-- Trigram similarity: matching an act by title when no hard identifier exists.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Diacritic-insensitive normalisation for the same matching path.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- Lets a scalar column share a GiST index with a range column. Without it,
-- `USING gist (draft_id, valid_period)` cannot be created at all.
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE SCHEMA IF NOT EXISTS platform;

-- Extensions are deliberately not dropped: they are database-wide and other
-- schemas depend on them, so undoing this changeset must not take them away.
--rollback DROP SCHEMA platform;
