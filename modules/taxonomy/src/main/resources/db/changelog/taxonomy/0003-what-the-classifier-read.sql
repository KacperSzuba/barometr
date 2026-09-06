--liquibase formatted sql

--changeset kacper:taxonomy-0003-what-the-classifier-read
--comment: The words that caught a verdict, so the queue can be worked through.
-- A verdict below the acceptance threshold goes to a person, and until now it reached
-- them as a subject id, a code and a number. "Is act 8f3c… about construction" is not a
-- question anybody can answer from that; they would have to find the act in another
-- system first, for every row of a queue that a backlog run fills a thousand at a time.
--
-- The citation columns beside this one do not answer it either, and are not meant to:
-- they name a document version and a character range, which is what a classifier
-- reading a *body* points at. A classifier reading a title has no document to cite and
-- something better to say — the phrase it matched, in the words the lexicon holds them.
ALTER TABLE taxonomy.item_industry ADD COLUMN matched_on text;

-- A person's judgement is not a match on anything: they read the law and decided. A
-- phrase against a manual verdict would be this system inventing a reason on somebody
-- else's behalf, which is exactly what the method column exists to keep apart.
ALTER TABLE taxonomy.item_industry ADD CONSTRAINT ck_item_industry_matched_on
    CHECK (method = 'model' OR matched_on IS NULL);

--rollback ALTER TABLE taxonomy.item_industry DROP CONSTRAINT ck_item_industry_matched_on;
--rollback ALTER TABLE taxonomy.item_industry DROP COLUMN matched_on;
