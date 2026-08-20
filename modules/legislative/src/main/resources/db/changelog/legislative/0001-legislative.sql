--liquibase formatted sql

--changeset kacper:legislative-0001-legislative
--comment: Acts, drafts, and the path a draft takes through the process.
-- Acts, drafts and the path a draft takes through the legislative process.
--
-- The design goal is one question answerable in SQL alone: *what was the status
-- of draft X on day Y*. That needs two independent time axes — when something
-- was true, and when we found out — and a history that is never overwritten.

CREATE SCHEMA IF NOT EXISTS legislative;

CREATE TABLE legislative.act (
    id    uuid PRIMARY KEY,
    -- ELI is the canonical identifier, but only exists once an act is published;
    -- a draft lives for months before it has one, hence nullable.
    eli   text,
    title text NOT NULL,
    -- Lowercased, unaccented, punctuation stripped. Stored rather than computed
    -- so the trigram index below is usable and stable.
    title_normalised text NOT NULL,
    act_type  text NOT NULL,
    publisher text,
    announced_on  date,
    in_force_from date,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE UNIQUE INDEX ux_act_eli ON legislative.act (eli) WHERE eli IS NOT NULL;
-- Fuzzy matching for documents that carry no hard identifier: title similarity
-- is the fallback when a print number cannot be resolved to an ELI yet.
CREATE INDEX ix_act_title_trgm
    ON legislative.act USING gin (title_normalised gin_trgm_ops);

-- The alias table that makes "one act, three sources" work. The primary key is
-- the invariant: an identifier resolves to exactly one act, whoever issued it.
CREATE TABLE legislative.act_identifier (
    act_id      uuid          NOT NULL REFERENCES legislative.act (id) ON DELETE CASCADE,
    scheme      text          NOT NULL,
    value       text          NOT NULL,
    confidence  numeric(4, 3),
    resolved_by text          NOT NULL,
    resolved_at timestamptz   NOT NULL,

    PRIMARY KEY (scheme, value),
    CONSTRAINT ck_act_identifier_scheme
        CHECK (scheme IN ('eli', 'druk_sejmowy', 'rcl_id', 'dziennik_pozycja')),
    CONSTRAINT ck_act_identifier_resolved_by
        CHECK (resolved_by IN ('exact', 'fuzzy', 'manual')),
    CONSTRAINT ck_act_identifier_confidence
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1))
);

CREATE INDEX ix_act_identifier_act ON legislative.act_identifier (act_id);

-- Matches below the confidence threshold wait here for a human. Without this
-- queue the choice would be between wrong links and lost documents.
CREATE TABLE legislative.act_match_candidate (
    id          uuid          PRIMARY KEY,
    -- Cross-schema reference without a foreign key; see the rule in V4001.
    document_id uuid          NOT NULL,
    act_id      uuid          REFERENCES legislative.act (id) ON DELETE CASCADE,
    scheme      text,
    value       text,
    confidence  numeric(4, 3) NOT NULL,
    status      text          NOT NULL DEFAULT 'pending',
    reviewed_by text,
    reviewed_at timestamptz,
    created_at  timestamptz   NOT NULL,

    CONSTRAINT ck_match_status CHECK (status IN ('pending', 'accepted', 'rejected'))
);

CREATE INDEX ix_act_match_pending ON legislative.act_match_candidate (created_at)
    WHERE status = 'pending';

-- The change graph: what an act amends, repeals or consolidates. Walked with a
-- recursive CTE — a graph database only starts paying off past three levels,
-- and these queries are shallower than that.
CREATE TABLE legislative.act_relation (
    from_act_id uuid NOT NULL REFERENCES legislative.act (id) ON DELETE CASCADE,
    to_act_id   uuid NOT NULL REFERENCES legislative.act (id) ON DELETE CASCADE,
    relation    text NOT NULL,
    -- Provenance: the document, and the exact characters, establishing this edge.
    source_document_version_id uuid,
    char_start int,
    char_end   int,

    PRIMARY KEY (from_act_id, to_act_id, relation),
    CONSTRAINT ck_act_relation
        CHECK (relation IN ('amends', 'repeals', 'consolidates', 'implements')),
    CONSTRAINT ck_act_relation_not_self CHECK (from_act_id <> to_act_id),
    CONSTRAINT ck_act_relation_span
        CHECK ((char_start IS NULL) = (char_end IS NULL)
               AND (char_start IS NULL OR char_end > char_start))
);

-- Reverse direction — "what changed this act" is asked as often as the forward
-- question and would otherwise be a sequential scan.
CREATE INDEX ix_act_relation_to ON legislative.act_relation (to_act_id, relation);

CREATE TABLE legislative.draft (
    id     uuid PRIMARY KEY,
    -- Filled once the draft is published and acquires an ELI.
    act_id uuid REFERENCES legislative.act (id),
    title  text NOT NULL,
    title_normalised text NOT NULL,
    initiator text NOT NULL,
    term      int,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,

    CONSTRAINT ck_draft_initiator CHECK (
        initiator IN ('rzadowy', 'poselski', 'obywatelski', 'senacki', 'prezydencki', 'komisyjny')
    )
);

CREATE INDEX ix_draft_act ON legislative.draft (act_id) WHERE act_id IS NOT NULL;
CREATE INDEX ix_draft_title_trgm
    ON legislative.draft USING gin (title_normalised gin_trgm_ops);

-- Append-only. Never updated, never deleted: correcting a mistake means
-- recording a newer row with a later `known_at`, so the system can always show
-- both what happened and what it believed at any point.
CREATE TABLE legislative.stage_transition (
    id       uuid      PRIMARY KEY,
    draft_id uuid      NOT NULL REFERENCES legislative.draft (id) ON DELETE CASCADE,
    stage    text      NOT NULL,

    -- Valid time: when the draft actually was at this stage.
    valid_period tstzrange NOT NULL,
    -- Generated projections of the range, so ordinary queries and reports do not
    -- have to know about range operators.
    valid_from timestamptz GENERATED ALWAYS AS (lower(valid_period)) STORED,
    valid_to   timestamptz GENERATED ALWAYS AS (upper(valid_period)) STORED,

    -- Transaction time: when we learned it. The two together answer "what did we
    -- believe on day Z about day Y", which is what makes an alert defensible
    -- after the fact.
    known_at timestamptz NOT NULL,

    source_document_version_id uuid,
    char_start int,
    char_end   int,

    -- A transition the process model does not allow. Recorded rather than
    -- rejected: a draft can return to committee three times, and a schema that
    -- refuses reality simply loses data.
    is_exception boolean NOT NULL DEFAULT false,
    created_at   timestamptz NOT NULL,

    CONSTRAINT ck_stage_period
        CHECK (NOT isempty(valid_period) AND lower(valid_period) IS NOT NULL),
    CONSTRAINT ck_stage_span
        CHECK ((char_start IS NULL) = (char_end IS NULL)
               AND (char_start IS NULL OR char_end > char_start))
);

-- Answers `valid_period @> :day` directly. Needs btree_gist, because a uuid and
-- a range share the index.
--
-- Note what is deliberately *absent*: an EXCLUDE constraint forbidding
-- overlapping periods per draft. It would fit the shape of the data, but the
-- process genuinely produces overlaps and re-entries, and a constraint that
-- rejects them would turn messy reality into lost records. Validation lives in
-- the domain, with `is_exception` as the documented escape hatch.
CREATE INDEX ix_stage_transition_period
    ON legislative.stage_transition USING gist (draft_id, valid_period);

CREATE INDEX ix_stage_transition_known
    ON legislative.stage_transition (draft_id, known_at DESC);

-- Read model for "where is this draft now".
--
-- Every column is derivable from `stage_transition` and the table can be dropped
-- and rebuilt from it at any time; it exists so the hot query does not walk a
-- draft's entire history on every page load.
CREATE TABLE legislative.draft_status (
    draft_id      uuid        PRIMARY KEY
        REFERENCES legislative.draft (id) ON DELETE CASCADE,
    current_stage text        NOT NULL,
    entered_at    timestamptz NOT NULL,

    -- Estimates and hard deadlines are separate columns and never merged. A user
    -- treating a median-based guess as a statutory deadline is the one failure
    -- this product cannot afford, so the schema refuses to blur them.
    next_stage              text,
    next_stage_estimated_at timestamptz,
    hard_deadline_at        timestamptz,
    hard_deadline_kind      text,

    -- Set when a draft has not moved for longer than twice the median for its
    -- stage — "stuck" is a fact worth alerting on.
    stalled_since timestamptz,
    updated_at    timestamptz NOT NULL,

    CONSTRAINT ck_draft_status_deadline_kind
        CHECK ((hard_deadline_at IS NULL) = (hard_deadline_kind IS NULL))
);

CREATE INDEX ix_draft_status_stage ON legislative.draft_status (current_stage);
CREATE INDEX ix_draft_status_stalled ON legislative.draft_status (stalled_since)
    WHERE stalled_since IS NOT NULL;

--rollback DROP SCHEMA legislative CASCADE;
