--liquibase formatted sql

--changeset kacper:legislative-0007-consultation
--comment: When a draft is out for public comment, and the day comments are due.
-- The first thing in this system that looks forward rather than back. Everything
-- derived so far answers "what happened"; a consultation deadline answers "what you
-- have left to do about it", and that is the difference between a newsletter and a
-- product somebody keeps paying for.

-- ——— One consultation, as one document stated it ——————————————————————————
--
-- A row is created the moment a draft's card shows a public-consultation stage, and
-- carries no dates at all until a letter filed under that stage says what they are.
-- That empty row is not a placeholder: it is what a filed document is matched against,
-- and without it there is no way to tell the letter opening consultation from the
-- forty other files a ministry puts on the same page.
--
-- Nothing here is estimated. RPL prints no deadline field, so every date below was
-- read out of a letter's own words and is stored beside the words it was read from —
-- the same rule the whole corpus rests on, applied to the one claim a reader is most
-- likely to act on.
CREATE TABLE legislative.consultation (
    id           uuid PRIMARY KEY,
    draft_id     uuid NOT NULL REFERENCES legislative.draft (id) ON DELETE CASCADE,

    -- The source's own address for the stage this consultation is filed under — RPL
    -- calls it a catalog id, and it is baked into the address of every file filed
    -- there. It is what lets a document arriving from the archive find the
    -- consultation it belongs to, months after the card that created this row.
    source_catalog text NOT NULL,

    -- The day the letter went out, taken from its own dateline. Both the day the term
    -- runs from and, as far as this source can say, the day consultation opened.
    opened_on    date,

    -- The day comments are due, after `art. 57 § 4 k.p.a.` has moved it off a Saturday
    -- or a day off. Derived from the two columns above when the letter set a period,
    -- and deliberately stored anyway: the calendar and the alert run both order and
    -- filter by it, and the rule that moves it is Kotlin's rather than SQL's.
    closes_on    date,

    -- Set only when the letter stated a period — "w terminie 21 dni" — rather than a
    -- date. Kept because it is what the ministry actually wrote, and because a reader
    -- disputing the date needs to see the arithmetic and not only its result.
    days_allowed int,

    -- ——— The evidence ————————————————————————————————————————————————————
    -- Which version of which document said it, and where in that document. The
    -- offsets index the extracted text the corpus stored, exactly as a summary's
    -- citation does, so a reader can be shown the sentence rather than asked to
    -- trust it. No foreign key: corpus is another schema.
    --
    -- The document is kept beside the version because the two answer different
    -- questions. A dozen files are filed under one consultation stage, and the first
    -- of them whose words set a term is the one this row believes; a *later version
    -- of that same file* is a ministry correcting itself and replaces it. An impact
    -- assessment that happens to mention a date does not get to overwrite the
    -- covering letter, and without the document id there would be no way to tell the
    -- two cases apart.
    stated_document uuid,
    stated_by    uuid,
    char_start   int,
    char_end     int,
    quote        text,

    -- Where comments go, when the letter names an address for them. Usually null:
    -- most letters point at a form on the draft's own page, and a ministry's
    -- switchboard address picked out of a footer would be worse than nothing.
    submission_address text,

    known_at     timestamptz NOT NULL,

    -- One consultation per stage of a draft. A second row would be a second answer to
    -- "when are comments due", and the calendar has room for one.
    UNIQUE (draft_id, source_catalog),

    -- A date without the document it was read from is a date this system invented.
    CONSTRAINT ck_consultation_term_has_evidence
        CHECK ((closes_on IS NULL) = (stated_by IS NULL)),
    CONSTRAINT ck_consultation_quote_cited CHECK (
        (stated_by IS NULL AND stated_document IS NULL
            AND char_start IS NULL AND char_end IS NULL AND quote IS NULL)
        OR (stated_document IS NOT NULL
            AND char_start IS NOT NULL AND char_end > char_start AND quote IS NOT NULL)
    ),
    -- A period runs from a day. Without one it is not a term, it is a number.
    CONSTRAINT ck_consultation_period_counted
        CHECK (days_allowed IS NULL OR (days_allowed > 0 AND opened_on IS NOT NULL)),
    CONSTRAINT ck_consultation_closes_after_opening
        CHECK (closes_on IS NULL OR opened_on IS NULL OR closes_on >= opened_on)
);

-- The calendar's only query: what is still open, soonest first. Partial, because the
-- rows with no date are the ones waiting for a letter and no reader ever wants them.
CREATE INDEX ix_consultation_closing ON legislative.consultation (closes_on)
    WHERE closes_on IS NOT NULL;

--rollback DROP TABLE legislative.consultation;
