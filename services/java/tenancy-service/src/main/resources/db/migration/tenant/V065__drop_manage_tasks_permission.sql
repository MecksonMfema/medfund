-- Retires the claims:manage_tasks permission. The Tasks domain has been
-- removed from the sidebar + routes; the permission is no longer part of
-- the canonical catalogue (see Permissions.java, PermissionCatalogue.java,
-- permissions.yaml, and clients/angular/.../permissions.ts). Any dangling
-- role_permissions rows referencing it become orphaned and would trip the
-- RoleController's validation gate on the next PUT /roles/{id}/permissions,
-- so we sweep them here.
--
-- V006 seeded this key onto the tenant_admin role; that migration stays
-- untouched (feedback_never_edit_applied_migrations) — this DELETE is the
-- forward-only fix.

DELETE FROM role_permissions
WHERE permission = 'claims:manage_tasks';
