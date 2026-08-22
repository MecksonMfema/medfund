-- =====================================================================
-- V091: Reinsurance role_permissions seed (Phase 10 §A)
-- =====================================================================
-- Grants the seven finance.reinsurance:* keys added in Permissions.java /
-- permissions.yaml / permissions.ts to the built-in seed roles so the
-- reinsurance surface is usable out of the box; tenants can carve
-- narrower audiences via the role editor.
--
--   * tenant_admin      → every finance.reinsurance:* key.
--   * finance_officer   → view + record_recovery_received only. Higher-
--                         privilege ops (manage_treaty, approve_facultative,
--                         writeoff_recovery, resolve_review) are opt-in via
--                         tenant-defined roles (typically "reinsurance
--                         supervisor" — no system role is seeded for that
--                         because system-seed roles are code-defined in
--                         RoleService.seedDefaultRoles and the plan is
--                         silent on adding one).
--
-- Idempotent via ON CONFLICT (role_id, permission).

WITH admin_role AS (
    SELECT id FROM roles WHERE name = 'tenant_admin'
), admin_perms(permission) AS (
    VALUES
        ('finance.reinsurance:view'),
        ('finance.reinsurance:manage_treaty'),
        ('finance.reinsurance:cede_facultative'),
        ('finance.reinsurance:approve_facultative'),
        ('finance.reinsurance:record_recovery_received'),
        ('finance.reinsurance:writeoff_recovery'),
        ('finance.reinsurance:resolve_review')
)
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), admin_role.id, admin_perms.permission, 'full'
  FROM admin_role, admin_perms
ON CONFLICT (role_id, permission) DO NOTHING;

WITH officer_role AS (
    SELECT id FROM roles WHERE name = 'finance_officer'
), officer_perms(permission) AS (
    VALUES
        ('finance.reinsurance:view'),
        ('finance.reinsurance:record_recovery_received')
)
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), officer_role.id, officer_perms.permission, 'full'
  FROM officer_role, officer_perms
ON CONFLICT (role_id, permission) DO NOTHING;
