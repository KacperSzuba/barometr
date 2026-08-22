--liquibase formatted sql

--changeset kacper:corpus-0003-extracted-text
--comment: Make a half-extracted version impossible to record.
-- The three columns describing a version's plain text arrived with the schema and
-- nothing wrote them until now. Now that something does, the invariant is worth
-- stating where it cannot be forgotten: a version either has its text or it does
-- not.
--
-- A row with a hash and no length, or a length and no timestamp, is not a state any
-- reader knows how to interpret, and it is exactly what a partial failure between
-- two statements would leave behind.
ALTER TABLE corpus.document_version
    ADD CONSTRAINT ck_document_version_text_complete CHECK (
        (text_hash IS NULL AND text_length IS NULL AND extracted_at IS NULL)
        OR (text_hash IS NOT NULL AND text_length IS NOT NULL AND extracted_at IS NOT NULL)
    );

-- Zero is not a length this system records. A payload that yields no text at all —
-- a scan with no text layer, which is most of what municipal registers publish — is
-- left unextracted and counted, because a version carrying an empty text blob and no
-- chunks reads as "extracted" to everything downstream and is worse than an honest
-- null. It becomes extractable when OCR arrives, not before.
ALTER TABLE corpus.document_version
    ADD CONSTRAINT ck_document_version_text_length CHECK (text_length IS NULL OR text_length > 0);

--rollback ALTER TABLE corpus.document_version DROP CONSTRAINT ck_document_version_text_complete;
--rollback ALTER TABLE corpus.document_version DROP CONSTRAINT ck_document_version_text_length;
