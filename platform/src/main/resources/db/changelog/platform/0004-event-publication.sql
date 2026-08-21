--liquibase formatted sql

--changeset kacper:platform-0004-event-publication
--comment: Spring Modulith's register of events published between contexts.
-- Where an event published from one context to another is recorded until the
-- receiving context has handled it.
--
-- This is what makes `@ApplicationModuleListener` an outbox rather than a callback:
-- the publication is written in the publisher's transaction, the listener runs in
-- its own, and a listener that fails or a process that dies leaves a row Modulith
-- can resubmit. Without the table the first such listener fails at runtime — which
-- is why it arrives with the first one, in the same change.
--
-- Column shapes are Modulith's, not ours; it writes them, and it reads them with
-- statements this project never sees. This is the v2 layout, which is the default
-- from Modulith 2.0 unless `use-legacy-structure` is set. The `_archive` twin is
-- deliberately absent: it is only used when `spring.modulith.events.completion-mode`
-- is `ARCHIVE`, and this deployment completes in place.
--
-- In `platform` rather than `public`, like the ShedLock table, so that every
-- technical table lives where the technical schema is. `spring.modulith.events.jdbc.schema`
-- is what points the library at it.
CREATE TABLE platform.event_publication (
    id                     uuid                     NOT NULL PRIMARY KEY,
    listener_id            text                     NOT NULL,
    event_type             text                     NOT NULL,
    serialized_event       text                     NOT NULL,
    publication_date       timestamp with time zone NOT NULL,
    completion_date        timestamp with time zone,
    status                 text,
    completion_attempts    int,
    last_resubmission_date timestamp with time zone
);

-- Both indexes are Modulith's own, kept under its names: it looks a publication up
-- by the event it carries when completing one, and sweeps by completion date when
-- resubmitting. A hash index because the lookup is only ever an equality test on a
-- value far too long to want in a B-tree.
CREATE INDEX event_publication_serialized_event_hash_idx
    ON platform.event_publication USING hash (serialized_event);
CREATE INDEX event_publication_by_completion_date_idx
    ON platform.event_publication (completion_date);

--rollback DROP TABLE platform.event_publication;
