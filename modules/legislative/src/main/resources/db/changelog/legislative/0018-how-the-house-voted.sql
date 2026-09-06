--liquibase formatted sql
--
-- How the Sejm voted, and on what.
--
-- The connector has archived every voting of every sitting for as long as it has been
-- able to read a sitting: the numbers, the day, the majority the vote was held under
-- and the agenda item it belonged to. None of it was derived, so the one question a
-- reader asks about a bill that reached a vote — how did they vote — could be answered
-- only by somebody willing to read raw JSON out of the archive.
--
-- What the source does *not* say is whether a vote carried, and this schema does not
-- invent it. `majorityType` states the rule and `majorityVotes` states the threshold it
-- was measured against, but the two mean different things in different votes, and a
-- `passed` column computed here would be this system's claim wearing the Sejm's
-- clothes. The counts are recorded as given; a draft's outcome already comes from the
-- register's own closing word, which is a statement somebody actually made.

--changeset kacper:legislative-0018-how-the-house-voted
--comment: One row per voting of the Sejm, with the prints its agenda item cited.
CREATE TABLE legislative.vote (
    id            uuid NOT NULL PRIMARY KEY,

    -- Where in the term's record this vote sits. The source addresses a voting by all
    -- three and so does the archive, which is what makes them the natural key.
    term          int  NOT NULL,
    sitting       int  NOT NULL,
    voting_number int  NOT NULL,
    -- Which day of a sitting that runs over several. Null where the source omits it.
    sitting_day   int,

    taken_at      timestamptz NOT NULL,

    -- The agenda item, in the register's words — this is where the prints under debate
    -- are named, and it is the only place they are.
    title         text NOT NULL,
    -- What was actually being decided, where the register distinguishes it from the item
    -- as a whole: one agenda item holds a dozen votes on amendments to it.
    subject       text,

    -- The source's own vocabulary, stored as given: `ELECTRONIC`, `ON_LIST`,
    -- `TRADITIONAL`. No CHECK against a closed set and no enum beside it, deliberately —
    -- the Sejm may hold a vote by a method nobody here has heard of, and the archive
    -- would then hold a fact this table refused to keep.
    method        text NOT NULL,
    -- The rule the vote was held under, and the number of votes it needed. Both as
    -- stated; see the note above on what is not derived from them.
    majority      text,
    majority_votes int,

    yes               int NOT NULL,
    no                int NOT NULL,
    abstained         int NOT NULL,
    not_participating int NOT NULL,
    total_voted       int NOT NULL,

    -- The archived version this was read from, so every number above can be checked
    -- against the bytes it came from. A bare id across schemas, no foreign key, like
    -- every other cross-context reference here.
    document_version_id uuid NOT NULL,

    known_at      timestamptz NOT NULL,

    -- A voting is addressed by these three and a re-read restates them, so this is what
    -- makes reading a sitting twice cost nothing.
    CONSTRAINT ux_vote_identity UNIQUE (term, sitting, voting_number),
    CONSTRAINT ck_vote_counts CHECK (
        yes >= 0 AND no >= 0 AND abstained >= 0 AND not_participating >= 0 AND total_voted >= 0
    ),
    CONSTRAINT ck_vote_sitting_day CHECK (sitting_day IS NULL OR sitting_day > 0)
);

-- A term's votes, newest first: what a page of "how the house has been voting" reads.
CREATE INDEX ix_vote_newest ON legislative.vote (term, taken_at DESC);

--rollback DROP TABLE legislative.vote;

--changeset kacper:legislative-0018-prints-a-vote-cited
--comment: Which prints a vote's agenda item named, addressed as the archive addresses them.
--
-- Its own table rather than a column, because one vote can concern several prints —
-- "(druki nr 3, 4, 5, 6, 7 i 8)" is one vote on six of them — and because the join it
-- exists for is many-to-many from the other side too: a bill is voted on repeatedly.
--
-- The value stored is the print's *address*, `term10/print/424`, and not the number.
-- That is the same string `draft_identifier` holds under `druk_sejmowy`, so a vote finds
-- its draft by equality rather than by parsing a number out of one format and into
-- another. It also survives the ordering: a vote may be archived before the process that
-- creates the draft, and a row keyed on an address resolves whenever the draft arrives
-- rather than being lost for having arrived first.
CREATE TABLE legislative.vote_print (
    vote_id       uuid NOT NULL REFERENCES legislative.vote (id) ON DELETE CASCADE,
    print_address text NOT NULL,

    PRIMARY KEY (vote_id, print_address)
);

-- The join a draft's card makes: every vote that named this print.
CREATE INDEX ix_vote_print_address ON legislative.vote_print (print_address);

--rollback DROP TABLE legislative.vote_print;
