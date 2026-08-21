--liquibase formatted sql

--changeset kacper:sources-0005-register-isap
--comment: ISAP through the ELI API as registry data.
-- ISAP — Internetowy System Aktów Prawnych — seeded as registry data.
--
-- The third source, and the one that closes the path: Sejm shows a bill being
-- voted, RPL shows it being drafted, ISAP says what was actually enacted, when it
-- entered into force, and which acts it changed.
--
-- Enabled with a legal basis, unlike RPL, and for a reason that is a fact about the
-- source rather than a judgement about it: journals of law are published for
-- everyone by statute, and the API is the Chancellery of the Sejm's own
-- distribution of them. The same host and the same standing as the Sejm API row.

INSERT INTO sources.source (
    id, connector_id, name, base_url,
    legal_basis, license, attribution_required, commercial_use, review_date,
    refresh_interval, expected_min_records_per_run,
    enabled, created_at, updated_at
) VALUES (
    -- Fixed UUIDv7, as for the other two: every environment refers to this source
    -- by the same id, which keeps cursors and run history comparable across a restore.
    '01920000-0000-7000-8000-000000000003',
    'isap',
    'ISAP — Dziennik Ustaw i Monitor Polski (API ELI)',
    'https://api.sejm.gov.pl/eli',
    'Ustawa z 20.07.2000 o ogłaszaniu aktów normatywnych i niektórych innych aktów prawnych — dzienniki urzędowe udostępniane nieodpłatnie; ustawa z 6.09.2001 o dostępie do informacji publicznej',
    're-use of public sector information',
    true,
    true,
    -- Reviewed quarterly, per the source-register policy.
    '2026-11-21',
    -- Dziennik Ustaw is published on working days, several positions at a time.
    -- Hourly catches an act the morning it appears without polling an archive that
    -- moves once a day for most of its length.
    interval '1 hour',
    -- Deliberately NULL, as for the other two. A quiet weekend legitimately
    -- publishes nothing, and the runtime tells "the journals reported no change"
    -- apart from "the journals returned nothing" itself.
    NULL,
    true,
    now(),
    now()
)
ON CONFLICT (connector_id) DO NOTHING;

--rollback DELETE FROM sources.source WHERE connector_id = 'isap';
