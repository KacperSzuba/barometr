--liquibase formatted sql

--changeset kacper:legislative-0002-act-reference
--comment: The change graph, keyed by ELI, replacing an edge table that could not hold it.
-- What an act changes, repeals, consolidates or implements — keyed by ELI on both
-- sides.
--
-- This replaces `act_relation`, which was designed before the source was, and could
-- not hold what the source publishes. Its foreign keys required both ends to be acts
-- we already hold, while a reference from ISAP routinely points outside the archive:
-- "Akty zmieniające" on an act from 2011 names the act that amended it, and a
-- five-year backfill does not contain 2011. Under the old shape those edges were not
-- stored badly, they were not stored at all — and they are the majority of the
-- answers to "what changed this act", which is half the question this table exists
-- for.
--
-- The trade is explicit: no foreign key means nothing stops a reference to an ELI
-- that never existed. The CHECK constraints below make a malformed address
-- impossible, `act.eli` is unique, and acts are never deleted here, so what the key
-- would have bought is a dangling row we would have had to reject anyway. What it
-- would have cost is the edge itself.
--
-- Safe to drop rather than migrate: `act_relation` was never written to by anything,
-- and this repository has not been deployed.
DROP TABLE legislative.act_relation;

CREATE TABLE legislative.act_reference (
    -- The act that says so, which is always one we hold: a reference is read out of
    -- that act's own archived metadata.
    from_eli   text NOT NULL,
    -- The act referred to, which may be one we have never ingested.
    to_eli     text NOT NULL,
    relation   text NOT NULL,

    -- Provenance: the exact version of the document this edge was read from. No
    -- character range, deliberately — the source states references as structured
    -- fields, not as prose, so there is no span to point at and a column nothing can
    -- fill is worse than no column. An edge later extracted from a text will need
    -- one, and can add it then.
    source_document_version_id uuid NOT NULL,
    recorded_at timestamptz NOT NULL,

    PRIMARY KEY (from_eli, to_eli, relation),
    CONSTRAINT ck_act_reference_from CHECK (from_eli ~ '^[A-Z]{2,4}/[0-9]{4}/[0-9]+$'),
    CONSTRAINT ck_act_reference_to CHECK (to_eli ~ '^[A-Z]{2,4}/[0-9]{4}/[0-9]+$'),
    CONSTRAINT ck_act_reference_not_self CHECK (from_eli <> to_eli),
    CONSTRAINT ck_act_reference_relation
        CHECK (relation IN ('amends', 'repeals', 'consolidates', 'implements'))
);

-- "What does this act change" is the primary key's own prefix. The reverse question,
-- "what changed this act", is asked just as often and would otherwise be a scan.
CREATE INDEX ix_act_reference_to ON legislative.act_reference (to_eli, relation);

--rollback DROP TABLE legislative.act_reference;
