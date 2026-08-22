--liquibase formatted sql

--changeset kacper:platform-0005-job-trace-context
--comment: The trace a job belongs to, carried across the gap between queueing and running.
-- A queue is where a trace normally ends. The request that enqueued the work returns,
-- its span closes, and minutes later another thread on another machine does the work
-- under a trace of its own — so "follow this document from the fetch to the alert"
-- becomes three unrelated traces and a guess.
--
-- The W3C `traceparent` of whoever queued it, stored beside the payload and restored by
-- the worker. Opaque here on purpose: the queue does not parse it, it carries it.
ALTER TABLE platform.job
    ADD COLUMN trace_context text;

--rollback ALTER TABLE platform.job DROP COLUMN trace_context;
