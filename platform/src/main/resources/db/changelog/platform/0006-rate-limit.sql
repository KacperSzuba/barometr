--liquibase formatted sql

--changeset kacper:platform-0006-rate-limit
--comment: A token bucket per caller, in the database rather than in a process.
-- What stops one caller from taking the public API away from everybody else.
--
-- **In Postgres rather than in memory.** A bucket held in a process is a bucket per
-- instance: two replicas mean twice the limit, and an autoscaler means whatever it feels
-- like. The alternative usually reached for is Redis, which is a second piece of
-- infrastructure to run, back up and reason about for a table with two columns — and at
-- the volumes a public API for a Polish legislative tracker will see, one indexed upsert
-- per request is not the bottleneck. Redis becomes right when it is; the point of writing
-- this down is that the trade is deliberate.
--
-- **Refill is arithmetic, not a job.** The row records what was left and when it was last
-- refilled; the next request works out how much has accrued since. Nothing has to run on
-- a schedule, an idle bucket costs nothing, and a caller who disappears for a month is
-- back to full without anybody having topped them up.

CREATE TABLE platform.rate_limit_bucket (
    -- Who is being limited, in whatever vocabulary the caller was identified by:
    -- `key:<id>` for an API key, `ip:<address>` for an anonymous caller.
    bucket_key  text        PRIMARY KEY,
    -- Whole tokens left. One request is one token.
    tokens      int         NOT NULL,
    refilled_at timestamptz NOT NULL,

    CONSTRAINT ck_rate_limit_tokens CHECK (tokens >= 0)
);

-- Buckets nobody has touched for a while, which the sweep removes: a bucket at full is
-- indistinguishable from one that never existed, and an anonymous caller's address should
-- not become a row this system keeps for ever.
CREATE INDEX ix_rate_limit_idle ON platform.rate_limit_bucket (refilled_at);

--rollback DROP TABLE platform.rate_limit_bucket;
