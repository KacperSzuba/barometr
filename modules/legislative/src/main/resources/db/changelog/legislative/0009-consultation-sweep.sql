--liquibase formatted sql
--
-- What a consultation needs in order to be answered from the archive rather than from
-- an event.
--
-- A deadline is read when a letter's text is derived, and that is a single moment
-- that does not come again. Two things miss it. A letter derived before the card that
-- opened its consultation finds nothing to attach to and is dropped; and every letter
-- already in the archive when this feature was written was derived before there was
-- anything to attach to at all — no event will ever be raised for those, and their
-- consultations would stay blank for good.
--
-- Both are the same problem seen twice: the answer is in the archive and only an
-- arriving event ever asks the question. These two columns are what lets something ask
-- it on purpose.

--changeset kacper:legislative-0009-consultation-sweep
--comment: The archive address of a consultation, and when it was last looked up there.
ALTER TABLE legislative.consultation
    -- Where the draft's card lives in the archive — `projekt/ustawa/12409051` — from
    -- which the address of the stage's catalog page, and of every file filed beneath
    -- it, is a matter of appending segments.
    --
    -- Stored rather than derived: `source_catalog` names the folder and the draft's
    -- identifiers name the project, but nothing in this schema records which *kind* of
    -- draft it is, and that segment sits in the middle of every address. Null on rows
    -- opened before this column existed; a card is re-read every six hours, and the
    -- restatement fills it in.
    ADD COLUMN source_address text,

    -- When the archive was last searched for this consultation's letter. Null means
    -- never.
    --
    -- Kept so the sweep does not read the same dozen documents every half hour for the
    -- consultations whose letters genuinely state no term — a real and permanent
    -- category, since a ministry can file a draft for comment without saying anywhere
    -- how long there is to reply.
    ADD COLUMN swept_at timestamptz;

-- The sweep's only query: the ones with no date, least recently looked for first.
-- Partial, because a consultation that has been dated is never asked about again and
-- those are eventually most of the table.
CREATE INDEX ix_consultation_undated ON legislative.consultation (swept_at NULLS FIRST)
    WHERE closes_on IS NULL;

--rollback DROP INDEX legislative.ix_consultation_undated;
--rollback ALTER TABLE legislative.consultation DROP COLUMN swept_at;
--rollback ALTER TABLE legislative.consultation DROP COLUMN source_address;
