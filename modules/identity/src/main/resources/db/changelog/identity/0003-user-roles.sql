--liquibase formatted sql

--changeset kacper:identity-0003-user-roles
--comment: Roles become rows, so they can be constrained, indexed and queried.
-- What a user is allowed to do, as data the database understands.
--
-- The column this replaces held 'USER,OPERATOR' in a varchar(255). Nothing stopped
-- it saying 'OPERTAOR', or the same role twice, or a role no code has ever heard of;
-- "who are the operators" could not be asked without scanning every row and splitting
-- strings; and granting a role meant reading a string, editing it and writing it back,
-- with two administrators racing to lose each other's change.
--
-- Roles are a closed set rather than a lookup table on purpose: a role nobody's code
-- checks for grants nothing, so adding one is a change to the application and to this
-- constraint, together, in one commit. That is the same arrangement `payload_kind`
-- and the job statuses already use.
--
-- Granting one, until there is an interface for it:
--   INSERT INTO identity.user_roles (user_id, role, granted_at)
--   VALUES ('<user id>', 'OPERATOR', now());
CREATE TABLE identity.user_roles (
    user_id    uuid        NOT NULL REFERENCES identity.users (id) ON DELETE CASCADE,
    role       text        NOT NULL,
    -- Who held what and since when is the first question asked after an incident.
    granted_at timestamptz NOT NULL,

    PRIMARY KEY (user_id, role),
    CONSTRAINT ck_user_roles_known CHECK (role IN ('USER', 'OPERATOR'))
);

-- Answers "who can start a backfill" without reading the whole table.
CREATE INDEX ix_user_roles_role ON identity.user_roles (role);

-- Carries across whatever the column happened to hold, ignoring the empty entries a
-- trailing comma leaves behind.
INSERT INTO identity.user_roles (user_id, role, granted_at)
SELECT existing.id, trim(role), existing.created_at
FROM identity.users existing,
     unnest(string_to_array(existing.roles, ',')) AS role
WHERE trim(role) <> '';

ALTER TABLE identity.users DROP COLUMN roles;
--rollback ALTER TABLE identity.users ADD COLUMN roles varchar(255);
--rollback UPDATE identity.users u SET roles = COALESCE((SELECT string_agg(r.role, ',' ORDER BY r.role) FROM identity.user_roles r WHERE r.user_id = u.id), 'USER');
--rollback ALTER TABLE identity.users ALTER COLUMN roles SET NOT NULL;
--rollback DROP TABLE identity.user_roles;
