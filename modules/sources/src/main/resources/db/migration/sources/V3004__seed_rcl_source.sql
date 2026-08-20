-- Rządowy Proces Legislacyjny, seeded disabled and without a legal basis.
--
-- Both of those are the point. RCL publishes no API and its robots.txt disallows
-- every agent, so reading it is a decision somebody has to make and write down
-- rather than one a migration can make on their behalf. Seeding the row means the
-- source, its pace and its review date are already registry data; leaving
-- legal_basis NULL means ck_source_legal_basis_before_enabling refuses to let it
-- be switched on until that decision exists in writing.
--
-- To enable: set legal_basis to the actual ground for access, set review_date,
-- flip enabled, and set app.connectors.rcl.robots accordingly.

INSERT INTO sources.source (
    id, connector_id, name, base_url,
    legal_basis, license, attribution_required, commercial_use, review_date,
    refresh_interval, expected_min_records_per_run,
    enabled, created_at, updated_at
) VALUES (
    -- Fixed UUIDv7 so cursors and run history survive a restore, as for sejm.
    '01920000-0000-7000-8000-000000000002',
    'rcl',
    'Rządowy Proces Legislacyjny (RCL)',
    'https://legislacja.rcl.gov.pl',
    -- Deliberately NULL. See above.
    NULL,
    NULL,
    true,
    NULL,
    NULL,
    -- Government drafting moves in days; six hours is frequent enough to catch a
    -- consultation opening on the morning it opens.
    interval '6 hours',
    -- NULL for the same reason as sejm: a quiet weekend legitimately reads
    -- nothing, and the runtime tells "unchanged" apart from "empty" itself.
    NULL,
    false,
    now(),
    now()
)
ON CONFLICT (connector_id) DO NOTHING;
