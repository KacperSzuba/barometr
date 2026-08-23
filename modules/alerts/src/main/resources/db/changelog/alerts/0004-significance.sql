--liquibase formatted sql

--changeset kacper:alerts-0004-significance
--comment: How much a notification mattered, frozen at the moment it was decided.
-- Ordering by a number computed at read time would reshuffle a list every time it was
-- opened, and would rank last Tuesday's notification by where the draft stands this
-- Thursday. So the score is stored, and so are the reasons behind it: a weight changed
-- next month must not rewrite the explanation given for something already sent.
ALTER TABLE alerts.notification
    ADD COLUMN significance         int    NOT NULL DEFAULT 0,
    ADD COLUMN significance_reasons text[] NOT NULL DEFAULT '{}';

-- The default was for the rows already here, which were decided before anything scored
-- them and are honestly zero. Dropped now so that an insert forgetting the column fails
-- rather than quietly ranking something at the bottom forever.
ALTER TABLE alerts.notification
    ALTER COLUMN significance         DROP DEFAULT,
    ALTER COLUMN significance_reasons DROP DEFAULT;

-- Out of a hundred, and the database says so. The reasons are deliberately *not*
-- constrained to a list: the vocabulary grows every time a new signal becomes
-- computable — the size of a diff, the number of sources carrying a story — and a
-- CHECK here would make each of those a migration for no invariant worth the coupling.
ALTER TABLE alerts.notification
    ADD CONSTRAINT ck_notification_significance CHECK (significance BETWEEN 0 AND 100);

-- **The threshold, on the rule rather than on the profile.** A profile says what
-- somebody cares about; a rule says what they want waking up for, and "only the
-- important ones" is plainly the second. Zero is the honest default: somebody who has
-- not asked to hear less has not asked to hear less.
ALTER TABLE alerts.alert_rule
    ADD COLUMN minimum_significance int NOT NULL DEFAULT 0;

ALTER TABLE alerts.alert_rule
    ADD CONSTRAINT ck_alert_rule_minimum_significance
        CHECK (minimum_significance BETWEEN 0 AND 100);

--rollback ALTER TABLE alerts.alert_rule DROP COLUMN minimum_significance;
--rollback ALTER TABLE alerts.notification DROP COLUMN significance_reasons;
--rollback ALTER TABLE alerts.notification DROP COLUMN significance;
