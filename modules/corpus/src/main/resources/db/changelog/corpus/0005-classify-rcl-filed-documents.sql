--liquibase formatted sql
--
-- The files filed under RPL's stages, classified as what they are.
--
-- Every one of them — a draft's text, its justification, an impact assessment, the
-- letters sending it out for comment — was archived as `unknown`, because the reader
-- knew the four shapes of *page* RPL publishes and not the shape of the documents
-- filed beneath them. Each also logged a warning saying so, once per file, which on
-- this source is most of the archive.
--
-- The kind is written when a document is first recorded and never restated: a version
-- arriving later updates a title and nothing else, deliberately, so that a source
-- which stops sending something does not erase what we already knew. That makes this
-- correction a migration rather than something the next crawl fixes on its own.

--changeset kacper:corpus-0005-classify-rcl-filed-documents
--comment: Reclassify RPL's filed documents, which were archived before the reader knew them.
--
-- Anchored at both ends and narrowed to `unknown`, so this touches exactly the rows
-- the gap produced: a document already classified is one some other reader was sure
-- about, and this has no business overruling it.
UPDATE corpus.document
SET kind = 'rcl-filed-document'
WHERE kind = 'unknown'
  AND external_id ~ '^projekt/[^/]+/[^/]+/katalog/[^/]+/dokument/[^/]+$';

--rollback SELECT 1; -- `unknown` was the absence of an answer; restoring it restores the gap.
