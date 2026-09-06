--liquibase formatted sql

--changeset kacper:taxonomy-0002-classification-progress
--comment: How far the classifier has read into the archive, per lexicon version.
-- Classifying happens at the edge of the archive — an act is read as it is recorded —
-- and that leaves everything stored before the classifier existed, which on the day it
-- ships is the entire archive. The walk over it has to survive a restart and has to
-- know when it is finished, and neither is answerable from the verdicts alone: a
-- subject with no verdict is one nothing has read *or* one whose title says nothing
-- about any industry, and those are the same absence.
--
-- Keyed by lexicon version, so correcting the terms is what makes the archive worth
-- reading again: a new version has no progress recorded, the walk starts from the
-- beginning, and every subject is re-read against terms somebody has just fixed. The
-- old row stays as the record of what the previous version got through.
CREATE TABLE taxonomy.classification_progress (
    lexicon_version text NOT NULL,
    -- `act` or `draft`, walked separately because legislative pages them separately.
    subject_kind    text NOT NULL,
    -- Where the walk got to, in the identifier order legislative pages by. Null means
    -- it has not started, which is a different thing from having finished.
    last_subject_id uuid,
    -- Set once the walk has run out of archive. What stops an hourly job paging through
    -- a hundred thousand acts to discover there is nothing left to read.
    completed_at    timestamptz,
    updated_at      timestamptz NOT NULL,

    PRIMARY KEY (lexicon_version, subject_kind),
    CONSTRAINT ck_classification_progress_kind CHECK (subject_kind IN ('act', 'draft'))
);

--rollback DROP TABLE taxonomy.classification_progress;
