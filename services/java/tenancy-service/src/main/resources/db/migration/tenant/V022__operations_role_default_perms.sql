-- Default permission set for the operations role.
--
-- Operations staff are the day-to-day operators of a tenant — they enrol and
-- maintain members, manage groups and dependants, and view the resulting
-- billing/finance trails. The role is created by RoleService.seedDefaultRoles
-- with no permissions attached; this migration seeds a sensible default so
-- operations users are productive out of the box without a tenant admin
-- having to compose the permission set by hand.
--
-- The canonical role name is lowercase ('operations'), consistent with
-- 'tenant_admin' in V006. Some tenants were provisioned before the lowercase
-- convention landed in RoleService and have an uppercase 'OPERATIONS' row in
-- their roles table — we normalise that here so this migration's seed always
-- targets a single canonical row.

-- 1. Normalise legacy uppercase row, if any. (Best-effort — if 'operations'
-- already exists we leave the uppercase row alone; step 2 will then update
-- the existing lowercase row.)
UPDATE roles
SET name = 'operations'
WHERE name = 'OPERATIONS'
  AND NOT EXISTS (SELECT 1 FROM roles r2 WHERE r2.name = 'operations');

-- 2. Ensure the canonical 'operations' role exists.
INSERT INTO roles (id, name, display_name, description, is_system, keycloak_role_name)
VALUES (
    gen_random_uuid(),
    'operations',
    'Operations Staff',
    'Day-to-day operator role — manages members, dependants, and groups.',
    TRUE,
    'operations'
)
ON CONFLICT (name) DO UPDATE
    SET is_system          = TRUE,
        keycloak_role_name = COALESCE(roles.keycloak_role_name, 'operations');

-- 3. Grant the canonical operations permission set. Re-runs are safe via the
-- (role_id, permission) unique constraint.
WITH ops_role AS (
    SELECT id FROM roles WHERE name = 'operations'
), perms(permission) AS (
    VALUES
        -- Member lifecycle
        ('members:view'),
        ('members:create'),
        ('members:update'),
        ('members:deactivate'),
        ('members:view_dependants'),
        ('members:view_history'),
        -- Group + dependant administration (lives under billing in the catalogue)
        ('billing:view'),
        ('billing:manage_groups'),
        ('billing:manage_dependants')
)
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), ops_role.id, perms.permission, 'full'
FROM ops_role, perms
ON CONFLICT (role_id, permission) DO NOTHING;
