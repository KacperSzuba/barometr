--liquibase formatted sql
--
-- Where a walk over the archive got to.
--
-- Everything derived from RPL so far reaches the pages already stored by one of two
-- routes: a marker on the thing being derived — "this draft's card has been read" — or
-- a question that can be asked of the derived rows themselves. Recording what is filed
-- under a draft has neither. A stage folder holding no files is a real answer and
-- indistinguishable from one nobody has looked in, so a walk that asked the rows where
-- to resume would re-read every empty folder in the archive for ever, which is the
-- failure `ArchivedCardSweep` was written a second time to avoid.
--
-- A cursor answers it exactly. `DocumentCatalog` pages the archive by identity and
-- identities are time-ordered, so a walk that remembers the last document it read
-- resumes after it, cannot skip a document stored while it was running, and costs one
-- empty page per run once it has caught up.

--changeset kacper:legislative-0017-archive-walk
--comment: How far a sweep has walked the archive, so it can stop when it is done.
CREATE TABLE legislative.archive_walk (
    -- The walk's own name, so a second one over another kind of document is a row and
    -- not a table.
    walk           text PRIMARY KEY,

    -- The last document read. Null is the beginning of the archive, which is also what
    -- a walk that has never run means.
    after_document uuid,

    walked_at      timestamptz NOT NULL
);

--rollback DROP TABLE legislative.archive_walk;
