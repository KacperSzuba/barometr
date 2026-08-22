--liquibase formatted sql

--changeset kacper:alerts-0003-email-delivery
--comment: Digests leaving the building, and the addresses they must never leave for.
-- Two tables and one promise: a message goes out once, and an address that bounced or
-- complained never receives another. The second is not politeness — a sender that keeps
-- mailing dead addresses loses the reputation the rest of the product rides on.

-- **Nothing is ever sent here again.**
--
-- One list for three different sentences, because they mean the same thing to a mail
-- server: the address does not exist, the person said they did not want it, or the
-- person unsubscribed. Keyed on the address rather than the account — a bounce is a
-- fact about a mailbox, and it holds for whoever owns it next.
CREATE TABLE alerts.suppressed_address (
    address      text PRIMARY KEY,
    reason       text NOT NULL,
    -- What the provider actually said, kept verbatim: a suppression somebody disputes
    -- is unarguable only if the original words survive.
    detail       text,
    suppressed_at timestamptz NOT NULL,

    CONSTRAINT ck_suppressed_reason CHECK (reason IN ('bounced', 'complained', 'unsubscribed'))
);

-- **What was sent, and what happened.**
--
-- One row per digest, and the digest is the key: a run repeated after a crash, or two
-- instances whose locks overlapped, must not send the same summary twice, and the only
-- place that can be settled without a race is a unique constraint.
CREATE TABLE alerts.email_delivery (
    digest_id  uuid PRIMARY KEY REFERENCES alerts.digest (id) ON DELETE CASCADE,
    owner_id   uuid NOT NULL,
    address    text NOT NULL,
    -- `sent`, `failed` or `suppressed`. A digest nobody could be sent is recorded as
    -- such rather than left looking unprocessed for ever.
    status     text NOT NULL,
    detail     text,
    attempted_at timestamptz NOT NULL,

    CONSTRAINT ck_email_delivery_status CHECK (status IN ('sent', 'failed', 'suppressed'))
);

CREATE INDEX ix_email_delivery_owner ON alerts.email_delivery (owner_id, attempted_at DESC);

-- Failures are retried, so the sender asks for them by status.
CREATE INDEX ix_email_delivery_failed ON alerts.email_delivery (attempted_at)
    WHERE status = 'failed';

-- **The one-click way out.**
--
-- A capability in a URL: whoever holds it can stop the mail without signing in, which is
-- what `List-Unsubscribe` promises the receiving mail server. Random rather than derived
-- from the address, so that guessing one tells you nothing about anybody else's, and
-- stored rather than signed so there is no second secret to rotate.
CREATE TABLE alerts.unsubscribe_token (
    token      text PRIMARY KEY,
    owner_id   uuid NOT NULL UNIQUE,
    created_at timestamptz NOT NULL
);

--rollback DROP TABLE alerts.unsubscribe_token;
--rollback DROP TABLE alerts.email_delivery;
--rollback DROP TABLE alerts.suppressed_address;
