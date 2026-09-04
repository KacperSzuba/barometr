--liquibase formatted sql
--
-- Which RPL folder sits inside which. The one edge missing between a filed document
-- and the consultation it was filed under.
--
-- A stage is a tree rather than a folder: "Konsultacje publiczne" holds five child
-- folders — the draft, the letters sending it out, the positions filed in reply, the
-- applicant's answer to them, a conference — and the letter that states the deadline
-- is in the second of those, never in the stage itself. RPL addresses a file by the
-- folder it is filed in and nothing above it, so a letter arriving from the archive
-- names folder 13196868 and knows nothing of the 13196866 its consultation was opened
-- under. Without this table the two cannot be joined, and a deadline could only be
-- matched to a draft — which would let a letter filed under a different stage of the
-- same draft set the one date this product asks anybody to act on.

--changeset kacper:legislative-0008-catalog-folder
--comment: Which RPL folder sits inside which, read from the pages that say so.
--
-- Recorded for every catalog page the archive holds, not only for the consultation
-- stages, and that is deliberate. Listeners run concurrently on virtual threads, so a
-- stage's catalog page can be derived before the card that opens the consultation on
-- it; an edge recorded only when a consultation already existed would be missed for
-- good on that ordering. Storing the tree as it is seen makes the join answer the same
-- question whichever half arrives first.
--
-- The cost of that is roughly five rows per stage per draft, of two short ids. Cheap
-- against the alternative, which is a deadline this system read and then failed to
-- attach to anything.
CREATE TABLE legislative.catalog_folder (
    -- RPL's ids are site-wide, so the folder is the key: it sits in exactly one place,
    -- and a second parent for it would be a contradiction rather than a second fact.
    catalog_id        text PRIMARY KEY,
    parent_catalog_id text NOT NULL,
    known_at          timestamptz NOT NULL,

    -- A folder inside itself would make the join above loop.
    CONSTRAINT ck_catalog_folder_not_its_own_parent CHECK (parent_catalog_id <> catalog_id)
);

-- The lookup is by child — "what is this letter's folder inside of" — which the
-- primary key already serves. This index is for the other direction, the one a
-- consultation asks: every folder of mine.
CREATE INDEX ix_catalog_folder_parent ON legislative.catalog_folder (parent_catalog_id);

--rollback DROP TABLE legislative.catalog_folder;
