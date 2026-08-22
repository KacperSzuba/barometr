--liquibase formatted sql

--changeset kacper:audit-0001-audit-event
--comment: What was done, by whom, and what was refused — append-only, and chained.
-- The table exists to be trusted, which makes two of its properties more important
-- than anything it stores.
--
-- **Refusals are recorded, not just successes.** A guardrail nobody can see does not
-- build trust: "somebody tried to read another account's profile and was stopped" is
-- the entry that answers the question an audit log is bought for, and a log of
-- successful requests answers a different, easier one.
--
-- **Nothing here can be changed afterwards.** Not by the application, not by whoever
-- holds its credentials. That is enforced below by a trigger rather than only by
-- privileges, because the application connects as the owner of this schema and an
-- owner can grant its rights back to itself.

CREATE SCHEMA IF NOT EXISTS audit;

CREATE TABLE audit.audit_event (
    -- Monotonic, and the chain's order. A timestamp cannot play this part: two events
    -- in the same millisecond have no order, and the clock can move backwards.
    sequence   bigserial PRIMARY KEY,
    at         timestamptz NOT NULL,

    -- Null for somebody who never got as far as being anybody: a failed sign-in, a
    -- request with no token. Those are exactly the entries worth having.
    actor_id   uuid,
    -- What they presented as, when they presented anything — an e-mail from a failed
    -- login is what makes a run of them recognisable as one.
    actor_label text,

    -- What was attempted and what it was attempted on, in the vocabulary of the API:
    -- `POST` and `/api/v1/profiles/{id}` rather than a name this table invented.
    action     text NOT NULL,
    resource   text NOT NULL,
    outcome    text NOT NULL,
    -- The status code, kept because "denied" and "rejected" are the same word to a
    -- person and different numbers to whoever is debugging.
    status     int,

    -- The peer's address, which behind a reverse proxy is the proxy. A forwarded
    -- header is deliberately not read: without a list of trusted proxies it is a
    -- header anybody can set, and an audit log that records a claimed address as a
    -- fact is worse than one that records none.
    peer       text,

    -- The chain. Each row carries the hash of the one before it, so changing any row
    -- makes every later one disagree — and the table cannot be quietly rewritten even
    -- by somebody who can write to it.
    previous_hash text,
    hash          text NOT NULL,

    CONSTRAINT ck_audit_outcome
        CHECK (outcome IN ('succeeded', 'denied', 'rejected', 'failed'))
);

CREATE INDEX ix_audit_event_actor ON audit.audit_event (actor_id, sequence DESC);
-- The other question this table is asked: what was refused, lately, by anybody.
CREATE INDEX ix_audit_event_denied ON audit.audit_event (sequence DESC)
    WHERE outcome IN ('denied', 'rejected');

-- **Append only.**
--
-- A trigger rather than a `REVOKE`, because the application owns this schema and an
-- owner's privileges are its own to restore. A separate role that may only `INSERT` is
-- the second layer and belongs to the deployment; this one holds whoever is connected.
--
-- Deletion is refused as flatly as modification, including for old rows. Retention is
-- the obvious argument for allowing it, and nothing in this system yet states how long
-- these must be kept — deleting them on a guess is the single thing an audit log must
-- not do, so the rule stays absolute until somebody writes the period down.

--changeset kacper:audit-0002-refuse-change splitStatements:false
--comment: The function that says no. Its own changeset because the body is dollar-quoted, and splitting on semicolons would cut it in half.
CREATE FUNCTION audit.refuse_change() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'audit.audit_event is append-only: % is not permitted', TG_OP
        USING ERRCODE = 'insufficient_privilege';
END;
$$ LANGUAGE plpgsql;

--changeset kacper:audit-0003-append-only-triggers
--comment: What the function is attached to.
CREATE TRIGGER audit_event_is_append_only
    BEFORE UPDATE OR DELETE ON audit.audit_event
    FOR EACH ROW EXECUTE FUNCTION audit.refuse_change();

-- `TRUNCATE` does not fire a row-level trigger, and would empty the table in one
-- statement.
CREATE TRIGGER audit_event_is_not_truncatable
    BEFORE TRUNCATE ON audit.audit_event
    FOR EACH STATEMENT EXECUTE FUNCTION audit.refuse_change();

--rollback DROP TABLE audit.audit_event;
--rollback DROP FUNCTION audit.refuse_change();
--rollback DROP SCHEMA audit;
