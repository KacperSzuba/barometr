--liquibase formatted sql

--changeset kacper:taxonomy-0001-item-industry
--comment: Which industries an act or a draft concerns, and who said so.
-- The table the whole promise of impact routing rests on. A subscriber says "we are in
-- 41.20.Z"; nothing in a bill's title says that it is about construction, and until
-- something records the connection, an industry code is a subscription to silence.
--
-- Three decisions are worth stating before the table.
--
-- **The verdict is stored, not the reasoning.** Whatever decided — a person today, a
-- classifier later — writes a code, how sure it was, and what it read. Everything that
-- routes by industry then reads one answer instead of inferring its own from a title,
-- which is how a preview and an alert come to disagree.
--
-- **Below the threshold is a queue, not a silence.** A verdict nobody is confident
-- about is recorded as `pending` and waits for somebody to look at it. Dropping it
-- would leave no trace of the question having been asked, and accepting it would put a
-- guess in front of a lawyer.
--
-- **Provenance is a column, like everywhere else here.** A verdict may name the
-- document version and the characters it was read from, so "why is this act tagged
-- construction" has an answer that can be checked rather than asserted.

CREATE SCHEMA IF NOT EXISTS taxonomy;

CREATE TABLE taxonomy.item_industry (
    -- `act` or `draft`, in the vocabulary the rest of the system already uses.
    subject_kind text NOT NULL,
    -- Points at `legislative.act` or `legislative.draft` and declares no foreign key,
    -- like every other cross-schema reference here.
    subject_id   uuid NOT NULL,
    -- `62`, `62.0`, `62.01`, `62.01.Z`. The pattern is the one `PkdCode` enforces in
    -- Kotlin; the two are one rule in two places and change together.
    pkd          text NOT NULL,
    -- The digits with the dots taken out, which is what containment is a comparison of.
    -- Generated rather than written, so it cannot disagree with the code beside it, and
    -- indexed below because "everything under 62.0" is a prefix of exactly this.
    pkd_digits   text GENERATED ALWAYS AS (regexp_replace(pkd, '[^0-9]', '', 'g')) STORED,

    status text NOT NULL,
    -- How sure whoever decided was, from 0 to 1. A person recording a verdict says 1,
    -- and that is not a formality: it is what keeps "a lawyer looked at this" and "the
    -- model was fairly confident" from being the same row.
    confidence real NOT NULL,
    method text NOT NULL,
    -- Which model said so, so a bad release can be found and undone. Null for a person.
    model_version text,

    -- What was read, when whoever decided read something. Both null for a verdict from
    -- a person who used their judgement, which is an honest absence rather than a gap.
    document_version_id uuid,
    char_start          int,
    char_end            int,

    decided_at  timestamptz NOT NULL,
    reviewed_at timestamptz,

    -- One verdict per industry per subject: a second reading of the same code replaces
    -- the first rather than accumulating beside it.
    PRIMARY KEY (subject_kind, subject_id, pkd),

    CONSTRAINT ck_item_industry_subject_kind CHECK (subject_kind IN ('act', 'draft')),
    CONSTRAINT ck_item_industry_pkd CHECK (pkd ~ '^[0-9]{2}(\.[0-9])?([0-9])?(\.[A-Z])?$'),
    CONSTRAINT ck_item_industry_status CHECK (status IN ('accepted', 'pending', 'rejected')),
    CONSTRAINT ck_item_industry_confidence CHECK (confidence BETWEEN 0 AND 1),
    CONSTRAINT ck_item_industry_method CHECK (method IN ('manual', 'model')),
    -- A model verdict names its model, and a person's does not name one.
    CONSTRAINT ck_item_industry_model_version CHECK (
        (method = 'model' AND model_version IS NOT NULL)
        OR (method = 'manual' AND model_version IS NULL)
    ),
    -- A person deciding *is* the review. Leaving a manual verdict pending would put a
    -- human judgement in a queue waiting for a human judgement.
    CONSTRAINT ck_item_industry_manual_is_decided CHECK (method <> 'manual' OR status <> 'pending'),
    CONSTRAINT ck_item_industry_citation CHECK (
        (document_version_id IS NULL AND char_start IS NULL AND char_end IS NULL)
        OR (document_version_id IS NOT NULL AND char_start >= 0 AND char_end > char_start)
    ),
    CONSTRAINT ck_item_industry_reviewed CHECK (status = 'pending' OR reviewed_at IS NOT NULL OR method = 'model')
);

-- "What is in this industry, and everything under it" — the pull direction, which the
-- profile preview asks while somebody is still typing a code. `text_pattern_ops`
-- because the comparison is a prefix rather than an ordering.
CREATE INDEX ix_item_industry_digits ON taxonomy.item_industry (pkd_digits text_pattern_ops)
    WHERE status = 'accepted';

-- The review queue: small beside the table it lives in, and read on its own.
CREATE INDEX ix_item_industry_pending ON taxonomy.item_industry (decided_at)
    WHERE status = 'pending';

--rollback DROP TABLE taxonomy.item_industry;
--rollback DROP SCHEMA taxonomy;
