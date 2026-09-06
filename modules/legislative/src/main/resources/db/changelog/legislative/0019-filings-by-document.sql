--liquibase formatted sql
--
-- The way back from an archived file to the draft it was filed under.
--
-- `draft_filing` was written to answer "what is filed under this draft", and it is
-- indexed for exactly that. The opposite question — "which draft does this file belong
-- to" — is what anything deriving from the file itself has to ask, because that is what
-- it is holding: corpus announces a document, and nothing in the announcement says what
-- the document is about. Without this index that lookup is a sequential scan of every
-- filing in the archive, once per document, which is a walk of the whole table for every
-- page of a rebuild.

--changeset kacper:legislative-0019-filings-by-document
--comment: Finding a filing by the document it points at, for derivations that start from the file.
--
-- Partial, because the column is null for every filing a catalog page has named and the
-- archive does not hold — which is a large minority of the table and never an answer to
-- this question.
CREATE INDEX ix_draft_filing_document
    ON legislative.draft_filing (document_id)
    WHERE document_id IS NOT NULL;

--rollback DROP INDEX legislative.ix_draft_filing_document;
