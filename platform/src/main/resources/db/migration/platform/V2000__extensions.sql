-- Extensions the domain schema depends on. Runs before any module's tables.
--
-- Migration versions are namespaced by module ordinal so modules can evolve
-- independently without their Flyway versions ever colliding:
--   1xxx identity · 2xxx platform · 3xxx sources · 4xxx ingestion
--   5xxx corpus   · 6xxx legislative

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
