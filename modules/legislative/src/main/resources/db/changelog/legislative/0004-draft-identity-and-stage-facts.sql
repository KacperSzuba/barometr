--liquibase formatted sql

--changeset kacper:legislative-0004-draft-identity-and-stage-facts
--comment: A draft's aliases, and what a recorded stage needs to be a fact rather than a guess.
-- Three things the draft model needs before anything can be derived into it, all of
-- them learned from the data rather than guessed at a second time.

-- ——— A draft is one thing under several names ————————————————————————————
--
-- The same draft is `term10/print/424` in the Sejm and `UD383` in RPL, and it exists
-- in RPL for months before the Sejm has ever heard of it. Without an alias table the
-- second source creates a second draft, which is the failure `act_identifier` exists
-- to prevent one level up — so this is that table, in the same shape, for the same
-- reason.
CREATE TABLE legislative.draft_identifier (
    draft_id    uuid          NOT NULL REFERENCES legislative.draft (id) ON DELETE CASCADE,
    scheme      text          NOT NULL,
    value       text          NOT NULL,
    confidence  numeric(4, 3),
    resolved_by text          NOT NULL,
    resolved_at timestamptz   NOT NULL,

    PRIMARY KEY (scheme, value),
    CONSTRAINT ck_draft_identifier_scheme CHECK (scheme IN ('druk_sejmowy', 'rcl_id')),
    CONSTRAINT ck_draft_identifier_resolved_by
        CHECK (resolved_by IN ('exact', 'fuzzy', 'manual')),
    CONSTRAINT ck_draft_identifier_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX ix_draft_identifier_draft ON legislative.draft_identifier (draft_id);

-- ——— A seventh initiator, and an honest place for an eighth ————————————————
--
-- The closed set was written before any of this data had been read. The Sejm's own
-- register of term X holds 22 drafts of 305 introduced by the Presidium of the Sejm,
-- which is none of the six: not a committee, not a group of deputies, not the Senate.
--
-- `nieznany` joins it for a different reason. A draft whose title we cannot classify
-- is still a draft, and dropping it to keep the vocabulary tidy would lose from the
-- archive something the archive plainly contains. It is counted, so the gap argues
-- for itself rather than hiding.
ALTER TABLE legislative.draft DROP CONSTRAINT ck_draft_initiator;
ALTER TABLE legislative.draft ADD CONSTRAINT ck_draft_initiator CHECK (
    initiator IN (
        'rzadowy', 'poselski', 'obywatelski', 'senacki', 'prezydencki', 'komisyjny',
        'prezydium_sejmu', 'nieznany'
    )
);

-- ——— How a draft ended, which is not a stage it passed through ————————————
--
-- The register closes a process with a word — "Uchwalono", "Odrzucono" — and no date,
-- because it is a verdict on the whole passage rather than something that happened on
-- a day. Recorded as a stage it would sit in the timeline before the Senate and the
-- President, which is where the Sejm's vote is but not where the outcome belongs.
--
-- `closed_on` is the register's own closure date. A draft with neither is simply still
-- moving, which is the answer for most of them.
ALTER TABLE legislative.draft ADD COLUMN closed_on date;
ALTER TABLE legislative.draft ADD COLUMN outcome text;
ALTER TABLE legislative.draft ADD CONSTRAINT ck_draft_outcome
    CHECK (outcome IS NULL OR outcome IN ('uchwalony', 'odrzucony'));

-- ——— What a stage needs beyond its name ——————————————————————————————————

-- What the source called it. The model's vocabulary is ours and closed; this is the
-- words it was translated from, which is the only way to check a mapping after the
-- fact or to see what an unmapped stage actually said.
ALTER TABLE legislative.stage_transition ADD COLUMN source_label text;

-- Position in the source's own list of stages.
--
-- Not decoration: a draft can pass three stages in one day — second reading, back to
-- committee, third reading, all on 29 November 2023 in a real process — and a model
-- with day granularity cannot order them by time. Without this, "where is it now" has
-- no answer on the day it matters most.
ALTER TABLE legislative.stage_transition ADD COLUMN ordinal int NOT NULL DEFAULT 0;
ALTER TABLE legislative.stage_transition ALTER COLUMN ordinal DROP DEFAULT;

-- The identity of a recorded fact: this draft was at this stage over this period.
--
-- Restating it is what a re-read does every quarter of an hour, and appending it again
-- would turn the history into a pile of copies. A *correction* still appends — a
-- changed period is a different fact, kept beside the old one with a later `known_at`,
-- which is exactly what the two time axes are for.
CREATE UNIQUE INDEX ux_stage_transition_fact
    ON legislative.stage_transition (draft_id, stage, valid_period);

--rollback DROP INDEX legislative.ux_stage_transition_fact;
--rollback ALTER TABLE legislative.stage_transition DROP COLUMN ordinal;
--rollback ALTER TABLE legislative.stage_transition DROP COLUMN source_label;
--rollback ALTER TABLE legislative.draft DROP CONSTRAINT ck_draft_outcome;
--rollback ALTER TABLE legislative.draft DROP COLUMN outcome;
--rollback ALTER TABLE legislative.draft DROP COLUMN closed_on;
--rollback DROP TABLE legislative.draft_identifier;
