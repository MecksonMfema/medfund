-- RBAC refinements: tenants now define their own roles and assign permissions
-- from the canonical catalogue (see services/java/shared/src/main/resources/permissions.yaml).
--
-- This migration:
--   1. Adds the immutable `keycloak_role_name` column to `roles` so renaming a
--      role's display name doesn't break Keycloak realm-role mapping.
--   2. Seeds the `tenant_admin` role with every permission in the catalogue.
--      Tenants build the rest from the UI; only `tenant_admin` is platform-managed.
--
-- The previous seeding strategy (8 hardcoded Keycloak realm roles in
-- KeycloakRealmService) is being narrowed to just `tenant_admin` — see the
-- companion change in that service.

-- 1. Add keycloak_role_name. Stable identifier used when mirroring DB roles to
-- Keycloak realm roles. NULL is allowed during migration; the controller fills
-- it on every new role insert.
ALTER TABLE roles
    ADD COLUMN IF NOT EXISTS keycloak_role_name VARCHAR(64);

-- 2. Seed tenant_admin (idempotent — re-runnable on already-seeded tenants).
INSERT INTO roles (id, name, display_name, description, is_system, keycloak_role_name)
VALUES (
    gen_random_uuid(),
    'tenant_admin',
    'Tenant Admin',
    'Default administrator role with full access. Cannot be deleted.',
    TRUE,
    'tenant_admin'
)
ON CONFLICT (name) DO UPDATE
    SET is_system          = TRUE,
        keycloak_role_name = COALESCE(roles.keycloak_role_name, 'tenant_admin');

-- 3. Grant tenant_admin every permission in the canonical catalogue. Re-runs
-- are safe via the (role_id, permission) unique constraint + ON CONFLICT.
WITH admin_role AS (
    SELECT id FROM roles WHERE name = 'tenant_admin'
), perms(permission) AS (
    VALUES
        -- Claims
        ('claims:view'), ('claims:create'), ('claims:assess'), ('claims:adjudicate'),
        ('claims:reject'), ('claims:verify'), ('claims:view_drug'), ('claims:create_drug'),
        ('claims:adjudicate_drug'), ('claims:manage_preauth'), ('claims:manage_drug_preauth'),
        ('claims:manage_tariffs'), ('claims:manage_modifiers'),
        ('claims:manage_rejection_reasons'), ('claims:manage_verification_codes'),
        ('claims:assign'), ('claims:manage_tasks'),
        ('claims:view_ctc_payments'), ('claims:commit_ctc_payment'),
        -- Billing
        ('billing:view'), ('billing:manage_schemes'), ('billing:manage_age_groups'),
        ('billing:manage_waiting_periods'), ('billing:manage_groups'),
        ('billing:manage_dependants'), ('billing:generate_billing'),
        ('billing:view_statements'), ('billing:post_transactions'),
        ('billing:view_currencies'), ('billing:manage_currencies'),
        ('billing:view_creditors'), ('billing:manage_bad_debts'),
        -- Finance
        ('finance:view'), ('finance:create_payment_run'), ('finance:approve_payment_run'),
        ('finance:view_advance_payments'), ('finance:manage_advance_payments'),
        ('finance:manage_ctc_payments'), ('finance:manage_receipts'),
        ('finance:manage_banks'), ('finance:post_adjustments'),
        ('finance:view_debtors'), ('finance:view_subledger'),
        ('finance:manage_billing_reconcile'), ('finance:view_payment_advice'),
        ('finance:manage_copayments'), ('finance:view_withheld_tax'),
        -- Members
        ('members:view'), ('members:create'), ('members:update'), ('members:deactivate'),
        ('members:view_dependants'), ('members:manage_waivers'), ('members:view_history'),
        -- Providers
        ('providers:view'), ('providers:create'), ('providers:update'),
        ('providers:manage_contracts'),
        -- Tenant administration
        ('admin:manage_roles'), ('admin:manage_users'), ('admin:view_audit'),
        ('admin:manage_settings'), ('admin:manage_rules')
)
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), admin_role.id, perms.permission, 'full'
FROM admin_role, perms
ON CONFLICT (role_id, permission) DO NOTHING;
