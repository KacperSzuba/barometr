--liquibase formatted sql

--changeset kacper:identity-0004-session
--comment: One row per signed-in device, so somebody can see where they are logged in and end it.
-- A session is a refresh-token family, and this table is what that family looks like to
-- the person who owns it. The family already existed — it is what a replay revokes as a
-- unit — but it carries no name, no device and no last-seen, so "you are signed in on
-- four devices; this one is Warsaw, an hour ago" could not be answered at all.
--
-- Two decisions worth stating.
--
-- **The user agent is stored as sent, and not parsed.** A UA string is not a device
-- name, and reading one properly is a library with a data file that ages; reading one
-- badly is worse than showing the string. The client renders it; this records it.
--
-- **The address is kept because it is the point.** "Signed in from an address you have
-- never used" is the whole reason a session list exists, and it is personal data kept on
-- the lawful basis of securing the account — which is why it goes when the account does
-- (`ON DELETE CASCADE`) and why the sweep below revokes what nobody uses.

CREATE TABLE identity.session (
    -- The family every refresh token of this login belongs to. Not a foreign key
    -- because a family is not a table: it is the identifier those rows share.
    family_id    uuid        PRIMARY KEY,
    user_id      uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    user_agent   text,
    client_ip    inet,
    created_at   timestamptz NOT NULL,
    -- Moved on every refresh, which is the only signal this system has that somebody is
    -- still there: an access token is used without asking anybody.
    last_seen_at timestamptz NOT NULL,
    revoked_at   timestamptz,

    CONSTRAINT ck_session_seen_after_start CHECK (last_seen_at >= created_at),
    -- Long enough for any real UA string and short enough that the column cannot be
    -- used as storage by whoever is sending it.
    CONSTRAINT ck_session_user_agent CHECK (user_agent IS NULL OR length(user_agent) BETWEEN 1 AND 400)
);

-- The list somebody reads: their own sessions, most recent first. Partial, because a
-- revoked session is history and the live set stays small however long an account lives.
CREATE INDEX ix_session_live ON identity.session (user_id, last_seen_at DESC)
    WHERE revoked_at IS NULL;

--rollback DROP TABLE identity.session;
