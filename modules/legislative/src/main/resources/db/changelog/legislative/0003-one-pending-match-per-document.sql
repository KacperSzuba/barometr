--liquibase formatted sql

--changeset kacper:legislative-0003-one-pending-match-per-document
--comment: A document waits in the review queue once, not once per delivery.
-- One document, at most one question for a reviewer.
--
-- Matching runs from an event, and events are redelivered: Spring Modulith replays
-- anything a listener did not complete, and a connector replay re-announces whole
-- years. Without this index each replay would add another identical row, and the
-- queue a person is supposed to work through would fill with copies of the same
-- decision.
--
-- Partial, because the constraint is about *waiting*: a document already reviewed and
-- rejected may legitimately come back with a better candidate later.
CREATE UNIQUE INDEX ux_act_match_one_pending_per_document
    ON legislative.act_match_candidate (document_id)
    WHERE status = 'pending';

--rollback DROP INDEX legislative.ux_act_match_one_pending_per_document;
