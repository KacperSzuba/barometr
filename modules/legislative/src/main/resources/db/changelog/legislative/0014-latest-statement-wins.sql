--liquibase formatted sql

--changeset kacper:legislative-0014-latest-statement-wins
--comment: Which of two statements made at the same instant is the one that stands.
-- `stage_transition_latest` picks the statement with the greatest `known_at`, and that
-- is the right rule until two of them share one. Then `DISTINCT ON` keeps whichever the
-- scan reached first, which is neither deterministic nor the newer row — and the case is
-- not hypothetical: a correction made in the same transaction as the reading that
-- prompted it carries the same instant by construction. A draft that had left the
-- government's process went on reading as still in it.
--
-- The row's own identifier breaks the tie, and it means something: ids here are UUIDv7,
-- so a later row sorts after an earlier one. `created_at` sits between the two because
-- it is the fact being appealed to — when this system wrote the row — and the id is what
-- settles it when even that is shared.
CREATE OR REPLACE VIEW legislative.stage_transition_latest AS
SELECT DISTINCT ON (draft_id, stage, ordinal, valid_from) *
FROM legislative.stage_transition
ORDER BY draft_id, stage, ordinal, valid_from, known_at DESC, created_at DESC, id DESC;

--rollback CREATE OR REPLACE VIEW legislative.stage_transition_latest AS
--rollback SELECT DISTINCT ON (draft_id, stage, ordinal, valid_from) * FROM legislative.stage_transition
--rollback ORDER BY draft_id, stage, ordinal, valid_from, known_at DESC;
