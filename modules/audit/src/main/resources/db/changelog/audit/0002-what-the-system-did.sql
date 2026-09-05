--liquibase formatted sql

--changeset kacper:audit-0002-what-the-system-did
--comment: Why, for the entries no request explains.
-- Everything in this table arrived through the filter that records requests, which is
-- most of what happens and not all of it. The two decisions this system makes about
-- somebody's sessions without being asked — a refresh token replayed, a device gone
-- quiet for longer than a session may — end every session the account has, and the
-- request that triggered them is recorded as a refused refresh, which is exactly what an
-- expired token looks like. "Why was I signed out everywhere on Tuesday" had no answer.
--
-- The reason goes in a column of its own rather than into `action` or `resource`,
-- because those two are the API's vocabulary — a method and a path — and a reason is
-- neither. Null for every entry a request explains, which is nearly all of them.
--
-- It is covered by the hash like every other field, and appended to the material only
-- when it is present: an entry written before this column existed hashes exactly as it
-- did, so the chain over the whole table still verifies. Nothing can be moved between
-- fields to forge that, because none of them may contain the separator.
ALTER TABLE audit.audit_event ADD COLUMN detail text;

--rollback ALTER TABLE audit.audit_event DROP COLUMN detail;
