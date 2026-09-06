--liquibase formatted sql

--changeset kacper:legislative-0015-act-source-document
--comment: Which archived document an act was read from, so what changed in it can be found.
-- Corpus compares every version of every document it has text for, records what moved
-- editorially, and publishes the result. Nothing outside corpus reads any of it — the
-- port, the event and the five types beside them have no consumer — because the one
-- thing needed to use them is missing everywhere: which act or draft a document is.
--
-- The projector knows. It is handed the document version it is reading, uses it to cite
-- the references it records, and then drops the rest. Keeping the document id is what
-- lets an act's card answer "what changed in the newest text of this law" without
-- anybody reconstructing an address: no context has to know that ISAP happens to
-- address an act by its ELI, which is ingestion's business and would be a fact copied
-- into two more contexts the day it was relied on.
--
-- A bare id across schemas, no foreign key, like every other cross-context reference
-- here. Null for an act nothing has projected — one created by identity matching from a
-- print, before the journal published it.
ALTER TABLE legislative.act ADD COLUMN source_document_id uuid;

--rollback ALTER TABLE legislative.act DROP COLUMN source_document_id;
