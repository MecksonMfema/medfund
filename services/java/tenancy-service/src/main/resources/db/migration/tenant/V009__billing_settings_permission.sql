-- Append the new billing-catalogue permission to tenant_admin. V006 already
-- seeded the rest; this migration only adds the new key idempotently.

INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), r.id, 'billing:manage_billing_settings', 'full'
  FROM roles r
 WHERE r.name = 'tenant_admin'
ON CONFLICT (role_id, permission) DO NOTHING;
