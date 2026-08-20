--liquibase formatted sql

--changeset kacper:identity-0002-refresh-token-successors
--comment: A refresh inside the grace window issues a successor of its own.
-- A refresh inside the grace window issues a successor of its own.
--
-- The unique index dropped here assumed one successor per token, which is the one
-- thing the grace window cannot honour: only a token's SHA-256 is stored, never the
-- token, so a caller that lost the race cannot be handed the successor that already
-- exists — it does not exist in a form anyone can send. It is given a fresh token in
-- the same family instead: equally revocable, equally short-lived, and correct on any
-- number of instances.
--
-- What this replaces was an in-memory map of raw successors, documented as working
-- on one instance only. Lineage stays recorded; it is simply a tree now rather than
-- a chain, so the index becomes an ordinary one.
DROP INDEX identity.ux_refresh_tokens_predecessor;

CREATE INDEX ix_refresh_tokens_predecessor
    ON identity.refresh_tokens (predecessor_id)
    WHERE predecessor_id IS NOT NULL;

--rollback DROP INDEX identity.ix_refresh_tokens_predecessor;
--rollback CREATE UNIQUE INDEX ux_refresh_tokens_predecessor ON identity.refresh_tokens (predecessor_id);
