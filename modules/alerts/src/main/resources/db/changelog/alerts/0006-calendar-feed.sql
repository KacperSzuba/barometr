--liquibase formatted sql

--changeset kacper:alerts-0006-calendar-feed
--comment: A subscribable calendar of a profile's deadlines, and what has already been answered.
-- Two tables that turn the consultation calendar from something to look at into
-- something a working week is organised around.
--
-- **A feed is a capability, not a session.** A calendar client subscribes once and
-- fetches for years with no way to sign in — no headers a person can supply, no place
-- to put a bearer token, no way to be prompted. So the URL carries a random token and
-- the token is the whole authorisation, exactly as the unsubscribe link's is. What it
-- can reach is one profile's view of deadlines that are public to begin with; what it
-- cannot do is change anything.
--
-- **The token is stable, and that is the point.** A token that rotated on its own would
-- leave a subscribed calendar quietly refusing to update — the failure mode nobody
-- notices until a deadline has passed. Rotation is a thing somebody asks for, which
-- deletes the row and mints another.

CREATE TABLE alerts.calendar_feed (
    -- One feed per profile rather than per account: a regulatory team watches its own
    -- industry and its clients' separately, and the whole value of the feed is that the
    -- calendar it lands in is about one of them.
    profile_id uuid        PRIMARY KEY,
    -- Points at `identity.users`, and at `profiles.interest_profile` above, declaring no
    -- foreign key to either: integrity across a module boundary is the application's
    -- job, and an FK would weld three schemas into one migration order.
    owner_id   uuid        NOT NULL,
    token      text        NOT NULL,
    created_at timestamptz NOT NULL,

    CONSTRAINT ux_calendar_feed_token UNIQUE (token),
    -- 32 random bytes in url-safe base64. Shorter than that is guessable, and a
    -- guessable token is somebody else's watchlist.
    CONSTRAINT ck_calendar_feed_token CHECK (length(token) >= 40)
);

CREATE INDEX ix_calendar_feed_owner ON alerts.calendar_feed (owner_id);

-- "We have written in about this one."
--
-- The state that makes a deadline a task rather than a notice. It belongs to the person
-- rather than to the consultation: two subscribers watching the same draft have
-- answered it or not independently, and the ministry's record of who wrote in is not
-- something this system can see.
CREATE TABLE alerts.consultation_filing (
    owner_id        uuid        NOT NULL,
    -- Points at `legislative.consultation`; see the rule above.
    consultation_id uuid        NOT NULL,
    filed_at        timestamptz NOT NULL,
    -- What was filed, in the subscriber's own words. Optional, and short: this is a
    -- note beside a date, not a document store.
    note            text,

    PRIMARY KEY (owner_id, consultation_id),
    CONSTRAINT ck_consultation_filing_note CHECK (note IS NULL OR length(trim(note)) BETWEEN 1 AND 500)
);

--rollback DROP TABLE alerts.consultation_filing;
--rollback DROP TABLE alerts.calendar_feed;
