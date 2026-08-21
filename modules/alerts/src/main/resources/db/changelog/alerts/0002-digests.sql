--liquibase formatted sql

--changeset kacper:alerts-0002-digests
--comment: When somebody hears about it, as opposed to whether.
-- The engine decides who should be told; this decides when. They are separate because
-- the same match is worth an e-mail at once to one person and a line in Friday's
-- summary to another, and neither of them changed what they care about.

-- **How often, in whose time.**
--
-- A row per person rather than per person and channel: there is one channel today, and
-- a table shaped for channels that do not exist would be a promise the code does not
-- keep. The day a second one arrives, this grows a column and the changeset says so.
CREATE TABLE alerts.delivery_preference (
    owner_id   uuid PRIMARY KEY,
    mode       text NOT NULL,
    -- The local hour a daily or weekly digest closes at, and the day of the week a
    -- weekly one closes on. Both meaningless for the other modes, which is what the
    -- constraints below say out loud.
    at_hour    int,
    on_weekday int,
    -- Theirs, not the server's. A daily digest "at 8" that arrives at 10 because the
    -- server thinks in UTC is a broken promise, and the only place the answer exists is
    -- the person who set it.
    zone       text NOT NULL,
    -- Local hours between which nothing normal is sent. Null means no quiet hours;
    -- `quiet_from > quiet_to` wraps midnight, which is the usual case.
    quiet_from int,
    quiet_to   int,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT ck_delivery_mode CHECK (mode IN ('immediate', 'hourly', 'daily', 'weekly')),
    -- An hour is required exactly where it means something. Without this, "daily" with
    -- no hour is a digest that never closes, and nobody would find out by reading it.
    CONSTRAINT ck_delivery_at_hour CHECK (
        (mode IN ('daily', 'weekly') AND at_hour BETWEEN 0 AND 23)
            OR (mode IN ('immediate', 'hourly') AND at_hour IS NULL)
    ),
    CONSTRAINT ck_delivery_weekday CHECK (
        (mode = 'weekly' AND on_weekday BETWEEN 1 AND 7)
            OR (mode <> 'weekly' AND on_weekday IS NULL)
    ),
    CONSTRAINT ck_delivery_quiet CHECK (
        (quiet_from IS NULL AND quiet_to IS NULL)
            OR (quiet_from BETWEEN 0 AND 23 AND quiet_to BETWEEN 0 AND 23 AND quiet_from <> quiet_to)
    )
);

-- **One closed window.**
--
-- Carries the moment and nothing else. What is in it is the notifications pointing at
-- it, and when it covers is those notifications' own timestamps — a window recorded
-- twice, here and in its contents, is a window that can disagree with itself.
CREATE TABLE alerts.digest (
    id         uuid PRIMARY KEY,
    owner_id   uuid NOT NULL,
    created_at timestamptz NOT NULL
);

CREATE INDEX ix_digest_owner ON alerts.digest (owner_id, created_at DESC);

-- Which window a notification went out in, and null while it is still waiting for one.
--
-- The notification table is therefore the buffer the specification asks for, rather
-- than a second queue beside it. That matters for the thing it asks to be able to do:
-- somebody switching from immediate to daily keeps everything already matched and
-- simply waits for the new window, because nothing about a waiting notification says
-- which mode was in force when it was raised.
ALTER TABLE alerts.notification
    ADD COLUMN digest_id uuid REFERENCES alerts.digest (id) ON DELETE SET NULL;

CREATE INDEX ix_notification_waiting ON alerts.notification (owner_id, created_at)
    WHERE digest_id IS NULL;

-- **How much it matters that this arrives now.**
--
-- Chosen by the person on the rule, not computed: nothing here scores significance yet,
-- and a column filled by a model that does not exist would read as a guarantee. What it
-- does today is decide whether quiet hours apply — "wake me for this one" is a sentence
-- somebody can mean about a profile, and it needs no model to be true.
ALTER TABLE alerts.alert_rule
    ADD COLUMN urgency text NOT NULL DEFAULT 'normal';

ALTER TABLE alerts.alert_rule
    ADD CONSTRAINT ck_alert_rule_urgency CHECK (urgency IN ('normal', 'critical'));

-- Copied onto the notification when it is raised, because the rule can change
-- afterwards and a notification held back through the night must be judged by what was
-- asked at the time.
ALTER TABLE alerts.notification
    ADD COLUMN urgency text NOT NULL DEFAULT 'normal';

ALTER TABLE alerts.notification
    ADD CONSTRAINT ck_notification_urgency CHECK (urgency IN ('normal', 'critical'));

--rollback ALTER TABLE alerts.notification DROP COLUMN urgency;
--rollback ALTER TABLE alerts.alert_rule DROP COLUMN urgency;
--rollback ALTER TABLE alerts.notification DROP COLUMN digest_id;
--rollback DROP TABLE alerts.digest;
--rollback DROP TABLE alerts.delivery_preference;
