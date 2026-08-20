--liquibase formatted sql

--changeset kacper:sources-0002-register-sejm
--comment: The Sejm API as registry data.
-- The Sejm API, seeded as registry data.
--
-- A source is reference data the system cannot run without, so it belongs in a
-- migration rather than in a bootstrap script that may or may not have been run.
-- The legal basis is filled in here for the same reason the schema refuses to
-- enable a source without one: it is a fact about the source, not paperwork.

INSERT INTO sources.source (
    id, connector_id, name, base_url,
    legal_basis, license, attribution_required, commercial_use, review_date,
    refresh_interval, expected_min_records_per_run,
    enabled, created_at, updated_at
) VALUES (
    -- Fixed UUIDv7 so every environment refers to this source by the same id,
    -- which keeps cursors and run history comparable across a restore.
    '01920000-0000-7000-8000-000000000001',
    'sejm',
    'API Sejmu Rzeczypospolitej Polskiej',
    'https://api.sejm.gov.pl',
    'Ustawa z 6.09.2001 o dostępie do informacji publicznej — dane publiczne udostępniane przez Kancelarię Sejmu',
    're-use of public sector information',
    true,
    true,
    -- Reviewed quarterly, per the source-register policy.
    '2026-11-17',
    interval '15 minutes',
    -- Deliberately NULL. A poll of an unchanged term legitimately reads nothing,
    -- so a fixed floor would report an outage every quarter of an hour; the
    -- runtime distinguishes "unchanged" from "empty" instead.
    NULL,
    true,
    now(),
    now()
)
ON CONFLICT (connector_id) DO NOTHING;

--rollback DELETE FROM sources.source WHERE connector_id = 'sejm';
