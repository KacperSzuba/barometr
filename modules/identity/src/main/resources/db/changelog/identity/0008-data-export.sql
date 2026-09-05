--liquibase formatted sql

--changeset kacper:identity-0008-data-export
--comment: A copy of everything this system holds about somebody, made on request.
-- The machine-readable export a person may ask for, and the record of having asked. The
-- file itself goes to the exports bucket like every other blob; this row is what says
-- whose it is, whether it is ready, and when it stops being available.
--
-- **It expires, and that is a privacy decision rather than housekeeping.** An export is
-- the single most concentrated collection of somebody's data this system ever produces:
-- every profile, every alert, every session, in one file behind one URL. Leaving it
-- there for ever would mean the act of exercising a right quietly made the data easier
-- to steal. It lives a week, and the sweep that removes it takes the blob with it.

CREATE TABLE identity.data_export (
    id      uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    -- `requested`, `ready` or `failed`. A request that could not be assembled says so
    -- rather than staying "requested" for ever, which is indistinguishable from a queue
    -- that has stopped.
    status  text NOT NULL,
    -- Where the file is, once there is one. Null while it is being assembled.
    content_hash bytea,
    byte_size    bigint,
    detail       text,

    requested_at timestamptz NOT NULL,
    completed_at timestamptz,
    expires_at   timestamptz NOT NULL,

    CONSTRAINT ck_data_export_status CHECK (status IN ('requested', 'ready', 'failed')),
    CONSTRAINT ck_data_export_hash_length CHECK (content_hash IS NULL OR octet_length(content_hash) = 32),
    CONSTRAINT ck_data_export_ready CHECK (
        status <> 'ready' OR (content_hash IS NOT NULL AND byte_size IS NOT NULL AND completed_at IS NOT NULL)
    ),
    CONSTRAINT ck_data_export_window CHECK (expires_at > requested_at)
);

CREATE INDEX ix_data_export_user ON identity.data_export (user_id, requested_at DESC);

-- What the retention sweep reads: exports whose week is up, whatever became of them.
CREATE INDEX ix_data_export_expiry ON identity.data_export (expires_at);

--rollback DROP TABLE identity.data_export;
