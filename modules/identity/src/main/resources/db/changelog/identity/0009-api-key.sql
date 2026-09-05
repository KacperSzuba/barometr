--liquibase formatted sql

--changeset kacper:identity-0009-api-key
--comment: Keys for the public API, with a tier and the scopes they may use.
-- The public API's credential. Researchers, journalists and integrators get one; anybody
-- with none is still let in, more slowly, by address.
--
-- **A tier is a rate, not a permission.** All four see the same public data — that is what
-- makes it public — and what differs is how fast they may ask for it. Keeping the two
-- ideas apart is what stops "press access" from quietly meaning "extra data", which is a
-- promise this product cannot make to one newsroom without making it to all of them.
--
-- **Only the hash is stored.** A key is a bearer credential, like every other one here:
-- the plaintext exists for as long as it takes to show it once, and a database dump is
-- not a set of working keys.
--
-- **Usage is counted on the row.** "Which key is making all these requests" is the first
-- question a public API raises, and a counter beside the key answers it without a metric
-- per key — which would be a cardinality problem the day somebody scripts a hundred
-- registrations.

CREATE TABLE identity.api_key (
    id       uuid        PRIMARY KEY,
    owner_id uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    -- What the key is for, in the owner's own words: "skrypt do monitoringu", "redakcja".
    name     text        NOT NULL,
    key_hash varchar(64) NOT NULL,
    -- `registered`, `press` or `partner`, matching the Kotlin enum. Anonymous is not a
    -- tier here: it is the absence of a key.
    tier     text        NOT NULL,
    -- What it may do. `read` is the public reads; `bulk` is the whole-dataset downloads,
    -- which are the ones that cost real money to serve.
    scopes   text[]      NOT NULL,

    created_at   timestamptz NOT NULL,
    expires_at   timestamptz,
    revoked_at   timestamptz,
    last_used_at timestamptz,
    requests     bigint      NOT NULL DEFAULT 0,

    CONSTRAINT ux_api_key_hash UNIQUE (key_hash),
    CONSTRAINT ck_api_key_name CHECK (length(trim(name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_api_key_tier CHECK (tier IN ('registered', 'press', 'partner')),
    CONSTRAINT ck_api_key_scopes CHECK (
        cardinality(scopes) > 0 AND scopes <@ ARRAY['read', 'bulk']::text[]
    ),
    CONSTRAINT ck_api_key_requests CHECK (requests >= 0),
    CONSTRAINT ck_api_key_window CHECK (expires_at IS NULL OR expires_at > created_at)
);

-- The account's own list, and the lookup every public request makes.
CREATE INDEX ix_api_key_owner ON identity.api_key (owner_id, created_at DESC);

--rollback DROP TABLE identity.api_key;
