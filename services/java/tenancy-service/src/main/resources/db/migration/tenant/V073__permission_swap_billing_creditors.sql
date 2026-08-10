-- =====================================================================
-- V073: Permission catalogue swap for creditors/debtors naming (G7a).
--
-- Introduces:
--   * billing:view_debtors          — gates the renamed billing Debtors page
--   * finance:view_creditors        — gates the unified finance Creditors page
--   * finance:manage_payments       — gates Payment row-level actions
--                                     (revoke, mark-paid, cancel)
--   * finance:manage_payment_runs   — gates PaymentRun lifecycle actions
--                                     (create, approve, execute, cancel)
--
-- Removes:
--   * billing:view_creditors from role_permissions (aggressive cutover
--     per G7b — no dual-accept). Every tenant currently granted the old
--     key is auto-granted the two new keys in the same transaction so
--     no operator loses access.
--
-- Java + Angular catalogue changes are shipped in the same phase so
-- PermissionResolver stops recognizing the removed key on the next boot.
--
-- Note: V006 does not maintain a `permissions_catalogue` table — the
-- catalogue is code-defined in shared/Permissions.java. Only
-- role_permissions is touched here.
-- =====================================================================

-- 1. For every role currently granted billing:view_creditors, grant the
--    two new keys at the same access_level. Idempotent via ON CONFLICT.
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), rp.role_id, new_key, rp.access_level
  FROM role_permissions rp
 CROSS JOIN (VALUES ('billing:view_debtors'), ('finance:view_creditors')) AS n(new_key)
 WHERE rp.permission = 'billing:view_creditors'
ON CONFLICT (role_id, permission) DO NOTHING;

-- 2. Remove the stale grants. Permissions.java drops the constant in
--    this phase so RoleController rejects the key on next boot.
DELETE FROM role_permissions WHERE permission = 'billing:view_creditors';

-- 3. Grant the two new payment-management permissions to tenant_admin
--    so the existing operator-role graph continues to work when Phase 3
--    lands the annotations. Non-admin roles get these grants only via
--    explicit tenant configuration.
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), r.id, new_key, 'full'
  FROM roles r
 CROSS JOIN (VALUES ('finance:manage_payments'), ('finance:manage_payment_runs')) AS n(new_key)
 WHERE r.name = 'tenant_admin'
ON CONFLICT (role_id, permission) DO NOTHING;
