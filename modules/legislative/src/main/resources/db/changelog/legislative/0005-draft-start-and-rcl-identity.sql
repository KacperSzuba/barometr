--liquibase formatted sql

--changeset kacper:legislative-0005-draft-start-and-rcl-identity
--comment: When a draft began, and the three different numbers RPL is known by.
-- Two corrections, both learned by reading the second source rather than by thinking
-- harder about the first.

-- ——— When the draft began ————————————————————————————————————————————————
--
-- Both registers state it and neither states it the same way: the Sejm gives
-- `processStartDate`, RPL the day it created the draft. It is the one date an RPL card
-- carries that means a beginning — every other date on it is a last-modified stamp —
-- and without it a draft that has never reached the Sejm has no position in time at
-- all. `created_at` beside it is when *this system* first wrote the row, which is a
-- different fact and answers a different question.
ALTER TABLE legislative.draft ADD COLUMN started_on date;

-- ——— RPL is three numbers, not one ————————————————————————————————————————
--
-- `rcl_id` was named before any of them had been read, and it turns out to name
-- nothing precisely. The same draft carries:
--
--   RM-0610-102-23  the Council of Ministers' number, which is what the *Sejm's*
--                   register prints when it points back at RPL
--   12409051        RPL's own project id, which is what its URLs are built from
--   UD383           the ministry's number in its programme of work, which is what a
--                   person quoting the draft actually says
--
-- None of them appears on both sides: the Sejm knows the first, an RPL card shows the
-- second and third. Keeping them apart is what will let the two be joined later —
-- either by following RPL's own resolver or by matching titles — and a single vague
-- scheme would have made that join unprovable.
UPDATE legislative.draft_identifier SET scheme = 'rcl_rm' WHERE scheme = 'rcl_id';

ALTER TABLE legislative.draft_identifier DROP CONSTRAINT ck_draft_identifier_scheme;
ALTER TABLE legislative.draft_identifier ADD CONSTRAINT ck_draft_identifier_scheme
    CHECK (scheme IN ('druk_sejmowy', 'rcl_rm', 'rcl_projekt', 'wykaz_prac'));

--rollback ALTER TABLE legislative.draft_identifier DROP CONSTRAINT ck_draft_identifier_scheme;
--rollback ALTER TABLE legislative.draft_identifier ADD CONSTRAINT ck_draft_identifier_scheme CHECK (scheme IN ('druk_sejmowy', 'rcl_id'));
--rollback UPDATE legislative.draft_identifier SET scheme = 'rcl_id' WHERE scheme = 'rcl_rm';
--rollback ALTER TABLE legislative.draft DROP COLUMN started_on;
