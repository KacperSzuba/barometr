--liquibase formatted sql

--changeset kacper:profiles-0002-keyword-stems
--comment: A keyword as the index reads it, so a title can be matched without a search.
-- A profile is matched in two directions. Asking "what does this keyword find" is a
-- search, and the index answers it. Asking "does this one title carry that keyword" for
-- every profile in the system, on every document of every ingest cycle, would be the
-- same question turned into thousands of searches.
--
-- So the words are kept the way the index would store them — stemmed, stopwords
-- dropped — and the second question becomes array containment against a title stemmed
-- once. The stemming itself is still the index's: this column holds its answer, never a
-- second implementation of it.
--
-- Nullable because it is derived and filled on first use rather than on write. A
-- profile can be saved while the search node is down; it simply matches nothing by
-- keyword until a matching run fills this in, which is the same thing that would happen
-- anyway with no index to search.

ALTER TABLE profiles.profile_interest
    ADD COLUMN stems text[];

-- The matching query is `stems <@ <the title's stems>`, which is a containment search
-- GIN answers directly.
CREATE INDEX ix_profile_interest_stems ON profiles.profile_interest USING gin (stems)
    WHERE stems IS NOT NULL;

--rollback DROP INDEX profiles.ix_profile_interest_stems;
--rollback ALTER TABLE profiles.profile_interest DROP COLUMN stems;
