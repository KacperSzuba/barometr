-- Identity module schema.
--
-- Migration versions are namespaced by module ordinal (identity owns the 1xxx
-- range) so that modules can evolve their schemas independently without their
-- Flyway versions ever colliding.

CREATE SCHEMA IF NOT EXISTS identity;

CREATE TABLE identity.users (
    id            uuid         PRIMARY KEY,
    email         varchar(320) NOT NULL,
    -- BCrypt produces 60 characters; the headroom covers an algorithm change.
    password_hash varchar(72)  NOT NULL,
    roles         varchar(255) NOT NULL,
    enabled       boolean      NOT NULL,
    created_at    timestamptz  NOT NULL
);

CREATE UNIQUE INDEX ux_users_email ON identity.users (lower(email));

CREATE TABLE identity.refresh_tokens (
    id             uuid        PRIMARY KEY,
    user_id        uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    -- Only the SHA-256 is stored: a database dump alone yields no usable tokens.
    token_hash     varchar(64) NOT NULL,
    -- Every token descending from one login shares a family, so detecting replay
    -- can revoke the entire lineage in a single statement.
    family_id      uuid        NOT NULL,
    -- The token this one replaced; lets a racing refresh find its successor.
    predecessor_id uuid,
    expires_at     timestamptz NOT NULL,
    -- Set on rotation. A second use is either a request race or theft.
    used_at        timestamptz,
    revoked_at     timestamptz,
    created_at     timestamptz NOT NULL
);

CREATE UNIQUE INDEX ux_refresh_tokens_hash ON identity.refresh_tokens (token_hash);
CREATE INDEX ix_refresh_tokens_family ON identity.refresh_tokens (family_id);
CREATE INDEX ix_refresh_tokens_user ON identity.refresh_tokens (user_id);
-- One successor per token keeps the grace-window lookup unambiguous.
CREATE UNIQUE INDEX ux_refresh_tokens_predecessor ON identity.refresh_tokens (predecessor_id);
