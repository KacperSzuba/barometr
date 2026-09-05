--liquibase formatted sql

--changeset kacper:identity-0006-trusted-device
--comment: A device that has already answered the second factor, and may skip it for a month.
-- A second factor asked for on every sign-in from the same laptop, every day, is a
-- second factor people turn off. This is the standard bargain — "remember this device
-- for 30 days" — written down so that what it costs is visible rather than implied.
--
-- **What it costs, plainly.** Whoever holds this token can sign in with the password
-- alone. It is therefore a credential in its own right: only its SHA-256 is stored, it
-- expires whether or not it is used, it is revoked as a set when the second factor is
-- turned off or reset, and the person who owns the account can end all of them from one
-- route without touching anything else.
--
-- **It is not a session.** A session is a login that has already happened; this is
-- permission to make the next one with one factor. Keeping them apart means ending a
-- session cannot silently leave a way back in, and ending trust cannot silently sign
-- somebody out of the tab they are reading.

CREATE TABLE identity.trusted_device (
    id         uuid        PRIMARY KEY,
    user_id    uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    -- The SHA-256 of the token, never the token: the input is thirty-two random bytes,
    -- so a work factor would buy nothing a password's would — the same argument the
    -- refresh tokens and the recovery codes make.
    token_hash varchar(64) NOT NULL,
    -- What asked to be remembered, as it named itself. Shown to whoever is deciding
    -- whether to end it; not trusted for anything.
    user_agent text,
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at   timestamptz,

    CONSTRAINT ux_trusted_device_token UNIQUE (token_hash),
    CONSTRAINT ck_trusted_device_window CHECK (expires_at > created_at),
    CONSTRAINT ck_trusted_device_user_agent CHECK (user_agent IS NULL OR length(user_agent) BETWEEN 1 AND 400)
);

-- What an account still trusts: read when somebody looks at the list, and again on
-- every sign-in that presents a token. Partial, because expired and revoked rows are
-- history and the live set stays small.
CREATE INDEX ix_trusted_device_live ON identity.trusted_device (user_id, expires_at DESC)
    WHERE revoked_at IS NULL;

--rollback DROP TABLE identity.trusted_device;
