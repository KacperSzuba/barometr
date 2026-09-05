--liquibase formatted sql

--changeset kacper:corpus-0006-version-diff
--comment: What changed between two versions of a document, unit by unit.
-- The archive already knows that version four differs from version three — the unique
-- index on `(document_id, content_hash)` is what decided a new version existed at all.
-- What it cannot say is *what* changed, and a reader of a three-hundred-page bill is
-- asking nothing else.
--
-- Three decisions are worth stating before the tables.
--
-- **A change cites character ranges on both sides.** The same currency as every other
-- derived claim here: a range indexes the extracted text `document_version.text_hash`
-- addresses, never the source PDF, so a quote rendered from a change is byte-for-byte
-- what the parser compared. Storing the text of a change instead would be a second copy
-- that can drift from the archive it claims to describe.
--
-- **The reading is versioned, not the diff.** `reader_version` names the parser and the
-- alignment that produced these rows. Improving either does not rewrite history: the new
-- reading is computed beside the old one, and the previous rows stay valid as an account
-- of what we said at the time.
--
-- **Word-level changes are jsonb.** A redrafted annex produces hundreds of word spans per
-- unit and nobody ever asks for one of them alone — the question is always "what changed
-- inside this unit". A third table would be millions of rows serving a query nobody makes.

CREATE TABLE corpus.version_diff (
    id              uuid  PRIMARY KEY,
    document_id     uuid  NOT NULL REFERENCES corpus.document (id) ON DELETE CASCADE,
    -- Both sides of the comparison, in the order they were published: `from` is the
    -- older reading. Not necessarily adjacent versions — an operator may ask what
    -- changed across five revisions — which is why the pair is stored rather than
    -- derived from `previous_version_id`.
    from_version_id uuid  NOT NULL REFERENCES corpus.document_version (id) ON DELETE CASCADE,
    to_version_id   uuid  NOT NULL REFERENCES corpus.document_version (id) ON DELETE CASCADE,
    reader_version  int   NOT NULL,

    -- Counted here rather than aggregated on read: a list of changes is paged, and the
    -- headline ("41 changes, 3 of them substantive") is what a card shows without
    -- opening one.
    units_added         int NOT NULL,
    units_removed       int NOT NULL,
    units_modified      int NOT NULL,
    units_moved         int NOT NULL,
    substantive_changes int NOT NULL,

    computed_at timestamptz NOT NULL,

    -- Recomputing a pair under the same reading is free rather than doubled: the job
    -- queue retries, the event register redelivers, and both land here.
    CONSTRAINT ux_version_diff_pair UNIQUE (from_version_id, to_version_id, reader_version),
    CONSTRAINT ck_version_diff_distinct CHECK (from_version_id <> to_version_id),
    CONSTRAINT ck_version_diff_reader CHECK (reader_version >= 1),
    CONSTRAINT ck_version_diff_counts CHECK (
        units_added >= 0 AND units_removed >= 0 AND units_modified >= 0
        AND units_moved >= 0 AND substantive_changes >= 0
    )
);

-- "What is the newest thing we can say about this document's changes" — the read the
-- card makes, and the only one that is not by identity.
CREATE INDEX ix_version_diff_document ON corpus.version_diff (document_id, computed_at DESC);

CREATE TABLE corpus.unit_change (
    diff_id uuid NOT NULL REFERENCES corpus.version_diff (id) ON DELETE CASCADE,
    -- Document order of the change, numbered from one, so a reader walks the bill the
    -- way it is written rather than the way the rows happened to be inserted.
    ordinal int  NOT NULL,
    kind    text NOT NULL,
    -- What sort of editorial unit changed: an article, a paragraph, a point. Kept
    -- because "three articles were removed" and "three tirets were removed" are
    -- different news, and recovering it from the path would mean parsing a string.
    unit_kind text NOT NULL,
    -- False when the two readings differ only in whitespace, punctuation or the
    -- designator itself. This is the column the "only what matters" filter stands on:
    -- four hundred editorial corrections hiding three real changes is the failure mode
    -- of every diff view ever built.
    substantive boolean NOT NULL,

    -- The older side, absent exactly when the unit was added.
    from_path       text,
    from_char_start int,
    from_char_end   int,
    -- The newer side, absent exactly when the unit was removed. A unit whose path
    -- differs between the two sides was renumbered; that it is *the same unit* is what
    -- alignment decided, and the pair of paths is the evidence.
    to_path         text,
    to_char_start   int,
    to_char_end     int,

    -- How sure the alignment is, for a pair matched by content rather than by path.
    -- Null where the paths matched, because there was nothing to be unsure about.
    similarity real,

    -- Word-level spans inside a modified unit, each naming ranges on both sides.
    words jsonb,
    -- Set when a unit changed so completely that listing its words would be listing the
    -- unit: the row then carries the whole-unit span instead. Said out loud rather than
    -- left as an empty list, which would read as "nothing changed inside".
    words_truncated boolean NOT NULL DEFAULT false,

    PRIMARY KEY (diff_id, ordinal),
    CONSTRAINT ck_unit_change_ordinal CHECK (ordinal >= 1),
    CONSTRAINT ck_unit_change_kind CHECK (kind IN ('added', 'removed', 'modified', 'moved')),
    -- Which side a change must have is the whole meaning of its kind, so it is the
    -- database that holds it: a "removed" row carrying a new position is not a change
    -- any reader knows how to render.
    CONSTRAINT ck_unit_change_sides CHECK (
        (kind = 'added' AND from_path IS NULL AND to_path IS NOT NULL)
        OR (kind = 'removed' AND from_path IS NOT NULL AND to_path IS NULL)
        OR (kind IN ('modified', 'moved') AND from_path IS NOT NULL AND to_path IS NOT NULL)
    ),
    CONSTRAINT ck_unit_change_from_range CHECK (
        (from_path IS NULL) = (from_char_start IS NULL)
        AND (from_char_start IS NULL) = (from_char_end IS NULL)
        AND (from_char_start IS NULL OR (from_char_start >= 0 AND from_char_end > from_char_start))
    ),
    CONSTRAINT ck_unit_change_to_range CHECK (
        (to_path IS NULL) = (to_char_start IS NULL)
        AND (to_char_start IS NULL) = (to_char_end IS NULL)
        AND (to_char_start IS NULL OR (to_char_start >= 0 AND to_char_end > to_char_start))
    ),
    CONSTRAINT ck_unit_change_similarity CHECK (similarity IS NULL OR similarity BETWEEN 0 AND 1),
    -- Only a modified unit has words that changed. A moved one reads identically in its
    -- new place — that is what made it a move rather than a rewrite.
    CONSTRAINT ck_unit_change_words CHECK (words IS NULL OR kind = 'modified')
);

--rollback DROP TABLE corpus.unit_change;
--rollback DROP TABLE corpus.version_diff;
