--liquibase formatted sql

--changeset kacper:identity-0007-workspace
--comment: Accounts that belong to an organisation, and what that organisation may insist on.
-- A regulatory team does not buy four accounts; it buys a workspace with four seats and
-- expects to add a fifth without asking anybody. This is that: a workspace, who is in it
-- and in what capacity, the invitations that have gone out, and the two policies an
-- institutional customer asks about before signing anything — "can I insist on a second
-- factor" and "can I make sessions expire sooner than your default".
--
-- **A workspace role is not an application role.** `identity.user_roles` says what
-- somebody may do to this system — `OPERATOR` starts crawls. This says what they may do
-- to their own organisation's account. Keeping them apart is what stops an
-- administrator of one workspace from being an administrator of the product.
--
-- **Seats are counted, not enforced by a licence server.** The number is here so that
-- adding a member can be refused with a sentence somebody understands, and so that
-- billing has something local to read — the specification's rule for the payment work
-- is that entitlement is never a question asked of a payment provider mid-request.

CREATE TABLE identity.workspace (
    id      uuid PRIMARY KEY,
    name    text NOT NULL,
    -- How many people may be in it. A seat is a member, invited or joined: an invitation
    -- that has gone out is a seat somebody has already been promised.
    seats   int  NOT NULL,

    -- The two policies. Null on the second means "whatever the deployment says", which is
    -- different from a workspace that has chosen the same number and would keep it if the
    -- default moved.
    require_two_factor  boolean  NOT NULL DEFAULT false,
    session_idle_timeout interval,

    created_at timestamptz NOT NULL,

    CONSTRAINT ck_workspace_name CHECK (length(trim(name)) BETWEEN 1 AND 120),
    CONSTRAINT ck_workspace_seats CHECK (seats BETWEEN 1 AND 10000),
    CONSTRAINT ck_workspace_idle_timeout CHECK (
        session_idle_timeout IS NULL OR session_idle_timeout BETWEEN interval '5 minutes' AND interval '365 days'
    )
);

CREATE TABLE identity.workspace_member (
    workspace_id uuid        NOT NULL REFERENCES identity.workspace (id) ON DELETE CASCADE,
    user_id      uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    -- `owner`, `admin` or `member`, matching the Kotlin enum. One closed vocabulary in
    -- two places, changed together.
    role         text        NOT NULL,
    joined_at    timestamptz NOT NULL,

    PRIMARY KEY (workspace_id, user_id),
    CONSTRAINT ck_workspace_member_role CHECK (role IN ('owner', 'admin', 'member'))
);

CREATE INDEX ix_workspace_member_user ON identity.workspace_member (user_id);

CREATE TABLE identity.workspace_invitation (
    id           uuid        PRIMARY KEY,
    workspace_id uuid        NOT NULL REFERENCES identity.workspace (id) ON DELETE CASCADE,
    -- The address it was sent to, lowercased like every other address in this schema:
    -- an invitation to `Ewa@Example.com` is an invitation to the account held as
    -- `ewa@example.com`, or the acceptance check quietly stops working.
    email        text        NOT NULL,
    role         text        NOT NULL,
    -- Only the SHA-256 of the token in the link. The link is the whole authorisation, so
    -- a database dump must not be a set of usable invitations.
    token_hash   varchar(64) NOT NULL,
    invited_by   uuid        NOT NULL,
    created_at   timestamptz NOT NULL,
    expires_at   timestamptz NOT NULL,
    accepted_at  timestamptz,
    revoked_at   timestamptz,

    CONSTRAINT ux_workspace_invitation_token UNIQUE (token_hash),
    CONSTRAINT ck_workspace_invitation_role CHECK (role IN ('owner', 'admin', 'member')),
    CONSTRAINT ck_workspace_invitation_email CHECK (email = lower(email) AND position('@' IN email) > 1),
    CONSTRAINT ck_workspace_invitation_window CHECK (expires_at > created_at)
);

-- One open invitation per address per workspace: inviting somebody twice is a person
-- pressing a button twice, and two live links to the same seat is a seat counted twice.
CREATE UNIQUE INDEX ux_workspace_invitation_open ON identity.workspace_invitation (workspace_id, email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX ix_workspace_invitation_email ON identity.workspace_invitation (email)
    WHERE accepted_at IS NULL AND revoked_at IS NULL;

--rollback DROP TABLE identity.workspace_invitation;
--rollback DROP TABLE identity.workspace_member;
--rollback DROP TABLE identity.workspace;
