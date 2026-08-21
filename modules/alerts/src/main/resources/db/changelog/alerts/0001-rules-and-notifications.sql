--liquibase formatted sql

--changeset kacper:alerts-0001-rules-and-notifications
--comment: Who gets told what, what they were told, and why they were not.
-- The product's own promise is one notification about a matter rather than eight, so
-- most of what is here exists to *not* send something — and to be able to say
-- afterwards exactly why.

CREATE SCHEMA IF NOT EXISTS alerts;

-- **What a profile catches is not the same question as what somebody wants waking up
-- for.** A profile says "construction, Mazowieckie, these two acts"; a rule says "yes,
-- but only once a draft reaches the Senate". Keeping them apart is what lets one
-- profile feed a daily digest and a same-day alert with different thresholds later,
-- without the profile itself carrying settings that have nothing to do with interest.
CREATE TABLE alerts.alert_rule (
    id         uuid PRIMARY KEY,
    -- Both point across a module boundary and neither declares a foreign key, as
    -- everywhere else here: integrity across schemas is the application's job.
    owner_id   uuid    NOT NULL,
    profile_id uuid    NOT NULL,
    enabled    boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    -- One rule per profile until a rule carries a channel of its own. A second rule on
    -- the same profile would be a second copy of the same decision.
    UNIQUE (profile_id)
);

CREATE INDEX ix_alert_rule_owner ON alerts.alert_rule (owner_id);

-- Stages a draft has to have reached before this rule speaks. No rows means every
-- stage, which is the honest default: somebody who has not narrowed anything has not
-- asked to hear less.
CREATE TABLE alerts.alert_rule_stage (
    rule_id uuid NOT NULL REFERENCES alerts.alert_rule (id) ON DELETE CASCADE,
    stage   text NOT NULL,

    PRIMARY KEY (rule_id, stage)
);

-- **The buffer.** Events arrive one act at a time and matching runs in batches, so
-- what happened is written down first and decided about afterwards.
--
-- In the database rather than in memory, because a restart between "an act was
-- recorded" and "somebody was told about it" must lose neither.
CREATE TABLE alerts.pending_item (
    id           uuid PRIMARY KEY,
    kind         text NOT NULL,
    subject_id   text NOT NULL,
    recorded_at  timestamptz NOT NULL,
    -- Null until a run has decided about it. Kept afterwards rather than deleted: this
    -- row is the evidence that the run saw the thing at all.
    processed_at timestamptz,

    CONSTRAINT ck_pending_item_kind CHECK (kind IN ('act', 'draft'))
);

-- The queue a run drains, and nothing else: once processed, a row is history.
CREATE INDEX ix_pending_item_unprocessed ON alerts.pending_item (recorded_at)
    WHERE processed_at IS NULL;

-- The same thing recorded twice — a register restating an act it already published —
-- is one thing that happened, and the run that reads this must not queue it twice.
CREATE UNIQUE INDEX ux_pending_item_unprocessed ON alerts.pending_item (kind, subject_id)
    WHERE processed_at IS NULL;

-- **What somebody was told.**
--
-- `event_key` is the identity of the *news*, not of the document: a draft that moves to
-- a new stage is news, the same draft restated is not. Unique per owner, which is what
-- makes producing a notification idempotent — a run that dies halfway and is repeated
-- tells nobody anything twice.
CREATE TABLE alerts.notification (
    id              uuid PRIMARY KEY,
    owner_id        uuid NOT NULL,
    profile_id      uuid NOT NULL,
    -- The version of the profile that matched, so that "why am I being told this" has
    -- an answer that does not change when the profile does.
    profile_version int  NOT NULL,
    subject_kind    text NOT NULL,
    subject_id      text NOT NULL,
    title           text NOT NULL,
    -- What the person chose that caught this. A notification that cannot say what it
    -- was matched on is one nobody can act on or turn off.
    matched_kind    text NOT NULL,
    matched_value   text NOT NULL,
    event_key       text NOT NULL,
    -- The matter this is about, as opposed to the news: a draft's own identity, so two
    -- pieces of news about one draft can be recognised as one matter.
    case_key        text NOT NULL,
    created_at      timestamptz NOT NULL,
    read_at         timestamptz,

    UNIQUE (owner_id, event_key),
    CONSTRAINT ck_notification_subject_kind CHECK (subject_kind IN ('act', 'draft'))
);

-- What a person's list of alerts is, and what the twenty-four-hour question asks.
CREATE INDEX ix_notification_owner ON alerts.notification (owner_id, created_at DESC);

CREATE INDEX ix_notification_case ON alerts.notification (owner_id, case_key, created_at DESC);

-- **Why.**
--
-- Every decision, including — especially — the ones that sent nothing. "Why did I not
-- get an alert about this" is otherwise unanswerable, and it is the question support
-- gets asked.
CREATE TABLE alerts.alert_decision (
    id           uuid PRIMARY KEY,
    owner_id     uuid NOT NULL,
    profile_id   uuid NOT NULL,
    subject_kind text NOT NULL,
    subject_id   text NOT NULL,
    event_key    text NOT NULL,
    -- `raised` or `withheld`, and the code beside it says which rule decided.
    decision     text NOT NULL,
    reason       text NOT NULL,
    decided_at   timestamptz NOT NULL,

    CONSTRAINT ck_alert_decision CHECK (decision IN ('raised', 'withheld'))
);

CREATE INDEX ix_alert_decision_subject ON alerts.alert_decision (subject_kind, subject_id, decided_at DESC);

CREATE INDEX ix_alert_decision_owner ON alerts.alert_decision (owner_id, decided_at DESC);

--rollback DROP TABLE alerts.alert_decision;
--rollback DROP TABLE alerts.notification;
--rollback DROP TABLE alerts.pending_item;
--rollback DROP TABLE alerts.alert_rule_stage;
--rollback DROP TABLE alerts.alert_rule;
--rollback DROP SCHEMA alerts;
