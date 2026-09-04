--liquibase formatted sql
--
-- Room for the one alert that is about a date rather than about news.
--
-- Everything the buffer has held so far is something that *happened* — an act was
-- published, a draft moved — and the notification says so after the fact. A
-- consultation deadline is the opposite shape: nothing has happened, and that is
-- exactly why somebody needs telling, because the window in which they could act is
-- about to close. It is also the first thing this system tells anybody that is
-- actionable rather than informative, which is most of the difference between a
-- newsletter and a product somebody keeps paying for.

--changeset kacper:alerts-0005-consultation-in-buffer
--comment: A consultation closing soon is a third thing the buffer can hold.
--
-- The `CHECK` and `ConsultationNotice.KIND` are one fact in two places and change
-- together. Dropped and re-added rather than edited, because 0001 has been applied.
ALTER TABLE alerts.pending_item
    DROP CONSTRAINT ck_pending_item_kind;

ALTER TABLE alerts.pending_item
    ADD CONSTRAINT ck_pending_item_kind CHECK (kind IN ('act', 'draft', 'consultation'));

--rollback ALTER TABLE alerts.pending_item DROP CONSTRAINT ck_pending_item_kind;
--rollback ALTER TABLE alerts.pending_item ADD CONSTRAINT ck_pending_item_kind CHECK (kind IN ('act', 'draft'));

--changeset kacper:alerts-0005-notification-closes-on
--comment: The day a notification says comments are due, frozen as significance is.
--
-- Null on everything else, which is most rows: an act being published is not a
-- deadline for the reader.
--
-- Copied onto the notification rather than joined from legislative when a digest is
-- rendered, for the reason the significance column gives: what a person was told is a
-- record of a moment, and a date that moved underneath it would make last Tuesday's
-- e-mail disagree with the notification it was composed from. A ministry that extends
-- a consultation states a new deadline, which is new news and gets its own row.
ALTER TABLE alerts.notification
    ADD COLUMN closes_on date;

--rollback ALTER TABLE alerts.notification DROP COLUMN closes_on;
