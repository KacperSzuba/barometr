--liquibase formatted sql

--changeset kacper:corpus-0004-document-by-address
--comment: Find a document by the address its source knows it by, without naming the source.
--
-- The corpus is addressed by `(source_id, external_id)`, and that pair is the
-- identity: a document is one source's document, and the unique index says so. What
-- it cannot answer is the question a derivation asks when it goes back over the
-- archive — "what is at this address" — because such a caller holds an id it
-- reconstructed from what the source itself published and has no notion of a source
-- id, which belongs to a context it cannot see.
--
-- Without this index that lookup is a sequential scan of every document ever
-- archived, once per address, which turns a sweep over a few hundred consultations
-- into an afternoon of full scans.
CREATE INDEX ix_document_external_id ON corpus.document (external_id);

--rollback DROP INDEX corpus.ix_document_external_id;
