--liquibase formatted sql
--
-- When a draft's archived card was last read for the stages it puts out to comment.
--
-- A consultation is opened when a card is *projected*, and a card is projected when a
-- new version of it is archived. That is the right trigger and it has one blind spot:
-- a draft whose card has not changed since this feature was written produces no
-- version, no event and no consultation — for ever, or until the ministry happens to
-- touch the page. The whole calendar rests on those rows existing, so "for ever" is
-- the wrong answer.
--
-- This column is what lets something walk the archived cards on purpose and know
-- which it has already been through, so the walk finishes instead of starting over.

--changeset kacper:legislative-0010-consultations-read-from-card
--comment: Which drafts have had their card read for consultation stages.
ALTER TABLE legislative.draft
    -- Null means never read that way, which is every row that existed before this and
    -- exactly the set the sweep has to get through. Written by the sweep and by the
    -- projector alike: a card just projected has been read, and re-reading it out of
    -- the archive an hour later would be work for an answer already given.
    ADD COLUMN consultations_read_at timestamptz;

--rollback ALTER TABLE legislative.draft DROP COLUMN consultations_read_at;
