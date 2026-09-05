--liquibase formatted sql

--changeset kacper:legislative-0012-stage-fact-as-it-stands
--comment: Which statement about a stage is the one that stands, stated once for every reader.
-- `stage_transition` is append-only, and that is the design: a stage whose period turns
-- out to be different — because the next stage has since arrived and closed it — is
-- recorded again as a new fact with a later `known_at`, beside the one it corrects. Two
-- time axes, and "what did we believe on Tuesday about Monday" has an answer.
--
-- What was missing is the other question, the one every reader actually asks: what do we
-- believe *now*. Nothing did that job. A draft read twice — once while its first reading
-- was the last stage the register knew, once after the committee had it — carries two
-- statements about that first reading, one of them still saying the draft never left it,
-- and both reached the card. `RecordedStage` does not even carry `known_at`, so no caller
-- could have told them apart.
--
-- A view rather than the same DISTINCT ON written into two repositories: which statement
-- stands is one fact, and the reader that measures how long stages take and the reader
-- that renders a timeline have to agree about it. Corrections stay in the table, where
-- the bitemporal question is still answerable.
--
-- `draft_id` leads the key because it is what every reader filters on: Postgres pushes
-- the qualifier into the scan underneath the DISTINCT ON, so a card costs the same index
-- lookup it always did.
CREATE VIEW legislative.stage_transition_latest AS
SELECT DISTINCT ON (draft_id, stage, ordinal, valid_from) *
FROM legislative.stage_transition
ORDER BY draft_id, stage, ordinal, valid_from, known_at DESC;

--rollback DROP VIEW legislative.stage_transition_latest;
