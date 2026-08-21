--liquibase formatted sql

--changeset kacper:profiles-0001-interest-profile
--comment: What a subscriber cares about, and every version of it they have ever had.
-- The structure the whole impact routing stands on: which industries, which places,
-- which acts and which words a subscriber wants to hear about — and which they
-- explicitly do not.
--
-- Two decisions are worth stating before the tables.

CREATE SCHEMA IF NOT EXISTS profiles;

-- **A profile is versioned, and a version is immutable.**
--
-- Somebody who is told on Tuesday that an act concerns them must still be able to see
-- *why* on Friday, after they have edited the profile twice. An alert therefore cites
-- the version it was matched against, and a version is never rewritten — editing a
-- profile writes a new one. Without this, changing a PKD code would silently rewrite
-- the reason for every notification already sent.
--
-- **Interests share one table.**
--
-- An industry code, a place, a watched act and a keyword are the same shape — a string
-- somebody chose, and whether it includes or excludes — so they are one table with a
-- closed vocabulary rather than five tables that would each need the same versioning
-- and the same exclusion flag.

CREATE TABLE profiles.interest_profile (
    id       uuid PRIMARY KEY,
    -- Points at `identity.users` and declares no foreign key, like every other
    -- cross-schema reference here: integrity across a module boundary is the
    -- application's job, and a FK would weld two schemas into one migration order.
    owner_id uuid NOT NULL,
    name     text NOT NULL,
    -- The version an alert matches against today. History lives beside it.
    current_version int NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    -- One account, several profiles — a regulatory team watches its own industry and
    -- its clients' separately — but never two under one name.
    UNIQUE (owner_id, name),
    CONSTRAINT ck_interest_profile_name CHECK (length(trim(name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_interest_profile_version CHECK (current_version >= 1)
);

CREATE INDEX ix_interest_profile_owner ON profiles.interest_profile (owner_id);

CREATE TABLE profiles.profile_version (
    profile_id uuid        NOT NULL REFERENCES profiles.interest_profile (id) ON DELETE CASCADE,
    version    int         NOT NULL,
    created_at timestamptz NOT NULL,

    PRIMARY KEY (profile_id, version),
    CONSTRAINT ck_profile_version CHECK (version >= 1)
);

CREATE TABLE profiles.profile_interest (
    profile_id uuid    NOT NULL,
    version    int     NOT NULL,
    -- What sort of thing was chosen. Closed, because every kind needs a matching rule
    -- written for it and a kind nobody has written one for would silently match
    -- nothing.
    kind       text    NOT NULL,
    -- The chosen value in the vocabulary of its kind: a PKD code, a TERYT code, an
    -- ELI, a print address, a word.
    value      text    NOT NULL,
    -- "Everything in construction except this one act." An exclusion is not the
    -- absence of an interest; it is an interest in not being told.
    excluded   boolean NOT NULL DEFAULT false,

    PRIMARY KEY (profile_id, version, kind, value),
    FOREIGN KEY (profile_id, version)
        REFERENCES profiles.profile_version (profile_id, version) ON DELETE CASCADE,
    CONSTRAINT ck_profile_interest_kind
        CHECK (kind IN ('pkd', 'region', 'act', 'draft', 'keyword')),
    CONSTRAINT ck_profile_interest_value CHECK (length(trim(value)) BETWEEN 1 AND 200)
);

-- The query the routing runs: everything a live profile version cares about, and — the
-- other direction — every profile that cares about a given code.
CREATE INDEX ix_profile_interest_value ON profiles.profile_interest (kind, value)
    WHERE NOT excluded;

--rollback DROP TABLE profiles.profile_interest;
--rollback DROP TABLE profiles.profile_version;
--rollback DROP TABLE profiles.interest_profile;
--rollback DROP SCHEMA profiles;
