--liquibase formatted sql

--changeset kacper:legislative-0006-withdrawn-drafts
--comment: A draft taken back is not a draft voted down.
-- The register reports both as `passed: false`, and reading only that field made every
-- withdrawn draft look rejected — a claim about the Sejm that the Sejm never made.
-- Only the closing entry's own word tells them apart: "Wycofano" against "Odrzucono".
--
-- Found by the counter on unmapped stage labels rather than by inspection: three
-- withdrawals in the first hundred and forty processes read.
ALTER TABLE legislative.draft DROP CONSTRAINT ck_draft_outcome;
ALTER TABLE legislative.draft ADD CONSTRAINT ck_draft_outcome
    CHECK (outcome IS NULL OR outcome IN ('uchwalony', 'odrzucony', 'wycofany'));

--rollback ALTER TABLE legislative.draft DROP CONSTRAINT ck_draft_outcome;
--rollback ALTER TABLE legislative.draft ADD CONSTRAINT ck_draft_outcome CHECK (outcome IS NULL OR outcome IN ('uchwalony', 'odrzucony'));
