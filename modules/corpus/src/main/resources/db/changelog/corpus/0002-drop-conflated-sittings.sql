--liquibase formatted sql

--changeset kacper:corpus-0002-drop-conflated-sittings
--comment: Remove the document that eleven unrelated sittings were derived into.
-- The Sejm API answers `number: 0` for every sitting it has not numbered — the
-- National Assembly, ceremonial assemblies, sittings still only planned — and the
-- connector archived all eleven of term 10's under `term{n}/proceeding/0`. The corpus
-- derived exactly what it was given: one document with eleven versions chained to
-- each other, a revision history of eleven unrelated events, and provenance citing
-- "version 7" of nothing in particular.
--
-- The connector now addresses them by the first day they sit, so each arrives as a
-- document of its own on the next pass. This removes the one they were conflated into.
--
-- Only the derived rows. The payloads stay exactly where they are: the archive is
-- never deleted, which is what makes deleting derived data safe — the corpus is
-- rebuildable from it, and this is that rule being used rather than described.
--
-- The pattern is the Sejm archive's address shape and no other source writes it.
-- Versions and chunks go with the document; the schema cascades them.
DELETE FROM corpus.document
WHERE kind = 'proceeding'
  AND external_id ~ '^term[0-9]+/proceeding/0$';

--rollback SELECT 1; -- Derived data. What this removed comes back from the archive.
