--liquibase formatted sql

--changeset kacper:identity-0005-two-factor
--comment: A second factor that does not depend on anybody's phone network.
-- TOTP (RFC 6238), the recovery codes that keep a lost phone from being a lost account,
-- and the short-lived challenge that sits between the two factors.
--
-- **The shared secret is stored encrypted.** It is the whole of the second factor: a
-- database dump that yields secrets yields codes, and the point of a second factor is
-- that stealing the first one is not enough. Encrypted with a key the application holds
-- and the database does not, which is the same reasoning that stores only the SHA-256 of
-- a refresh token.
--
-- **Setting up and turning on are two different things.** `confirmed_at` is null while
-- somebody has scanned a QR code and not yet proved they can read it; until they do,
-- nothing about their sign-in changes. Enabling a second factor that the person cannot
-- actually produce is how an account is lost.
--
-- **A recovery code is single use, and its use is recorded.** Not deleted: "this code
-- was used on Tuesday" is exactly what somebody investigating a strange sign-in needs to
-- see, and a deleted row says nothing at all.

CREATE TABLE identity.totp_secret (
    user_id uuid PRIMARY KEY REFERENCES identity.users (id) ON DELETE CASCADE,
    -- Ciphertext, never the secret. Long enough for the encoding the encryptor produces.
    secret       text        NOT NULL,
    -- Null until the first correct code proves the authenticator was really set up.
    confirmed_at timestamptz,
    created_at   timestamptz NOT NULL,

    CONSTRAINT ck_totp_secret_present CHECK (length(secret) BETWEEN 1 AND 1000)
);

CREATE TABLE identity.recovery_code (
    user_id    uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    -- SHA-256 of the code as it was shown. The codes carry eighty bits of their own, so
    -- a work factor would buy nothing a password's would — the same argument the refresh
    -- tokens make.
    code_hash  varchar(64) NOT NULL,
    used_at    timestamptz,
    created_at timestamptz NOT NULL,

    PRIMARY KEY (user_id, code_hash)
);

-- Which of an account's codes are still worth anything, without reading the rest.
CREATE INDEX ix_recovery_code_unused ON identity.recovery_code (user_id) WHERE used_at IS NULL;

-- The gap between the password and the code: a first factor that has been proved, and
-- has not yet bought anything.
CREATE TABLE identity.login_challenge (
    id          uuid        PRIMARY KEY,
    user_id     uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    expires_at  timestamptz NOT NULL,
    consumed_at timestamptz,
    -- Six digits is a million guesses, and a challenge that could be guessed at until it
    -- expired would be a second factor in name only. Counted here because the count has
    -- to survive whichever instance answers the next attempt.
    attempts    int         NOT NULL DEFAULT 0,
    created_at  timestamptz NOT NULL,

    CONSTRAINT ck_login_challenge_attempts CHECK (attempts >= 0),
    CONSTRAINT ck_login_challenge_window CHECK (expires_at > created_at)
);

CREATE INDEX ix_login_challenge_user ON identity.login_challenge (user_id);

--rollback DROP TABLE identity.login_challenge;
--rollback DROP TABLE identity.recovery_code;
--rollback DROP TABLE identity.totp_secret;
