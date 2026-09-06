--liquibase formatted sql
--
-- What a ministry actually filed under a draft, and where to read it.
--
-- The archive has held these files since the connector learned to follow a catalog
-- page: a bill's text, its justification, the impact assessment, the letters sending it
-- out for comment, the tables of comments that came back. Everything a reader of a
-- draft would want is in there, and a reader of a draft's card cannot see that any of
-- it exists — the card shows a title, a stage and a timeline, and the documents the
-- process is actually made of reach nobody.
--
-- What was missing is the join. RPL addresses a file by the folder it sits in, corpus
-- addresses it by its own identifier, and no table said which draft the file belongs
-- to. Two pages state the two halves and neither states both: the stage's catalog page
-- names the file — what it is called, who filed it, the day it was filed — and the
-- archived file itself carries the address corpus keeps it under. So both halves are
-- written here, into one row, by whichever arrives first.

--changeset kacper:legislative-0016-what-was-filed
--comment: The files filed under a government draft, named by RPL and addressed by corpus.
--
-- Keyed by RPL's own ids rather than by `draft_id`, and for the reason
-- `catalog_folder` gives: these listeners run concurrently on virtual threads, so a
-- stage's catalog page can be read before the card that creates the draft. A row that
-- could only be written once a draft existed would be lost for good on that ordering,
-- and there is nothing to redeliver it — the archive is content-addressed, and a page
-- nobody edits never produces a second version. The project id resolves to a draft
-- through `draft_identifier`, which is where every other RPL id already resolves.
CREATE TABLE legislative.draft_filing (
    -- RPL's id for the draft, as it appears in every address beneath it.
    source_project  text NOT NULL,
    -- RPL's id for the file. Site-wide, and the only part of a filing worth addressing
    -- by: a name is edited and a date belongs to the content.
    source_document text NOT NULL,
    -- The folder the file is filed in, which is most of what the file means — a
    -- position submitted in reply sits in a different one from the bill it answers.
    source_catalog  text NOT NULL,

    -- ——— What the catalog page says ——————————————————————————————————————
    -- Null until that page has been read. The name is the whole of what tells a reader
    -- which of forty files is the bill, so a filing without one is not worth showing.
    file_name       text,
    author          text,
    -- Day resolution is all the page offers.
    filed_on        date,

    -- ——— What the archive says ———————————————————————————————————————————
    -- Corpus's identity for the file, written when the file itself is archived. Null
    -- for one the catalog names and the connector has not fetched — a format it
    -- declines, or a walk that has not reached it. A bare id across schemas, no
    -- foreign key, like every other cross-context reference here.
    document_id     uuid,

    known_at        timestamptz NOT NULL,

    PRIMARY KEY (source_project, source_document),

    -- A row that is neither named nor archived states nothing at all.
    CONSTRAINT ck_draft_filing_named_or_archived
        CHECK (file_name IS NOT NULL OR document_id IS NOT NULL)
);

-- The card's only query: this draft's filings, newest first. `NULLS LAST` because a
-- filing RPL has not dated is the least interesting one, not the most recent.
CREATE INDEX ix_draft_filing_newest
    ON legislative.draft_filing (source_project, filed_on DESC NULLS LAST);

--rollback DROP TABLE legislative.draft_filing;
