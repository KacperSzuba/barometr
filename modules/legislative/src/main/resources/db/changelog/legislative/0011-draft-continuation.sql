--liquibase formatted sql

--changeset kacper:legislative-0011-draft-continuation
--comment: The one draft the two registers each hold half of, and the joins nobody is sure enough to make.
-- A government draft spends months in RPL and then arrives in the Sejm as a print, and
-- neither register prints an identifier the other shows: the Sejm knows
-- `RM-0610-102-23`, an RPL card shows `12409051` and `UD383`. So the two are two rows,
-- and every reader who follows one of them loses the half of the story the other holds.
--
-- ——— Why a link and not one row ————————————————————————————————————————————
--
-- `draft_identifier` was written expecting the second register to adopt the first's
-- draft, and that is the right shape when the join is known before either row exists.
-- It is not what happens here: the two rows are months apart, and by the time the join
-- can be made at all — by title, or by a number that turns out to be shared — alerts,
-- profiles and the search index are already holding the ids of both. Merging would ask
-- every context that ever saw a draft id to learn that one of them has stopped
-- existing; a link asks nothing of them and answers the same question.
--
-- One row per pair, in both directions: a government draft becomes exactly one print,
-- and a print comes from exactly one government draft. The primary key states the
-- first and the unique index the second, so a second join is refused by the database
-- rather than by whichever projector happens to look.
CREATE TABLE legislative.draft_continuation (
    government_draft_id uuid          PRIMARY KEY REFERENCES legislative.draft (id) ON DELETE CASCADE,
    sejm_draft_id       uuid          NOT NULL REFERENCES legislative.draft (id) ON DELETE CASCADE,
    -- The same vocabulary as `draft_identifier.resolved_by`, and for the same reason:
    -- a similarity of 1.0 arrived at by comparing titles is not the claim a number
    -- both registers print is, and a reader of this table has to be able to tell.
    joined_by   text          NOT NULL,
    confidence  numeric(4, 3),
    joined_at   timestamptz   NOT NULL,

    CONSTRAINT ck_draft_continuation_joined_by CHECK (joined_by IN ('exact', 'fuzzy', 'manual')),
    CONSTRAINT ck_draft_continuation_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    -- A draft is not its own continuation. Without this a matcher comparing a draft
    -- against the whole table would join the closest title it found, which is itself.
    CONSTRAINT ck_draft_continuation_not_self CHECK (government_draft_id <> sejm_draft_id)
);

CREATE UNIQUE INDEX ux_draft_continuation_sejm
    ON legislative.draft_continuation (sejm_draft_id);

-- ——— The joins nobody is sure enough to make ———————————————————————————————
--
-- The same queue as `act_match_candidate` one level up, and it exists for the same
-- reason: without it the choice is between wrong joins and a story that stays in two
-- halves. Two drafts rather than a document and an act, because that is what is being
-- joined here.
CREATE TABLE legislative.draft_match_candidate (
    id                  uuid          PRIMARY KEY,
    government_draft_id uuid          NOT NULL REFERENCES legislative.draft (id) ON DELETE CASCADE,
    sejm_draft_id       uuid          NOT NULL REFERENCES legislative.draft (id) ON DELETE CASCADE,
    confidence          numeric(4, 3) NOT NULL,
    status              text          NOT NULL DEFAULT 'pending',
    reviewed_by         text,
    reviewed_at         timestamptz,
    created_at          timestamptz   NOT NULL,

    CONSTRAINT ck_draft_match_status CHECK (status IN ('pending', 'accepted', 'rejected')),
    CONSTRAINT ck_draft_match_not_self CHECK (government_draft_id <> sejm_draft_id)
);

-- Matching runs from an event and events are redelivered, so the pair is unique while
-- it waits: a replay finds the row already queued instead of filling the queue with
-- copies of one decision. Only while it waits — a pair a reviewer rejected and a later
-- reading proposes again is a second question, and hiding it would hide the evidence
-- that the thresholds are wrong.
CREATE UNIQUE INDEX ux_draft_match_pending
    ON legislative.draft_match_candidate (government_draft_id, sejm_draft_id)
    WHERE status = 'pending';

CREATE INDEX ix_draft_match_pending ON legislative.draft_match_candidate (created_at)
    WHERE status = 'pending';

--rollback DROP TABLE legislative.draft_match_candidate;
--rollback DROP TABLE legislative.draft_continuation;
