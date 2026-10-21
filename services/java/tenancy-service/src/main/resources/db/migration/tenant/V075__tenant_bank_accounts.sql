-- =====================================================================
-- V075: Retire "MASCA bank accounts" surface. The data plane is already
-- per-tenant (V016 masca_bank_accounts lives in the tenant schema); this
-- migration renames the table to tenant_bank_accounts via INSERT-SELECT,
-- adds label + notes columns, wires payment_runs.source_bank_account_id,
-- and swaps the finance:manage_banks permission for the tenant-admin
-- namespaced admin.bank_accounts:manage.
--
-- No changes to V016; it stays flyway-locked per
-- feedback_never_edit_applied_migrations.
--
-- No permissions_catalogue table exists — the catalogue is code-only
-- (permissions.yaml, Permissions.java, PermissionCatalogue.java,
-- Angular permissions.ts). Those four files are updated in the same
-- code drop as this migration.
-- =====================================================================

-- 1. Fresh table with label + notes. Same partial unique index shape as V016.
CREATE TABLE IF NOT EXISTS tenant_bank_accounts (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    bank_name       VARCHAR(200) NOT NULL,
    account_number  VARCHAR(50)  NOT NULL,
    branch_code     VARCHAR(50),
    swift_code      VARCHAR(50),
    account_name    VARCHAR(200) NOT NULL,
    currency_code   VARCHAR(3)   NOT NULL,
    label           VARCHAR(120) NOT NULL,
    notes           TEXT,
    is_nominated    BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tenant_bank_accounts_number UNIQUE (account_number, currency_code)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_bank_accounts_nominated_per_currency
    ON tenant_bank_accounts(currency_code) WHERE is_nominated = TRUE;

-- 2. Move data across; backfill label from bank_name + currency_code.
INSERT INTO tenant_bank_accounts
    (id, bank_name, account_number, branch_code, swift_code, account_name,
     currency_code, label, notes, is_nominated, is_active, created_at, updated_at)
SELECT
    id, bank_name, account_number, branch_code, swift_code, account_name,
    currency_code,
    bank_name || ' ' || currency_code AS label,
    NULL::TEXT                        AS notes,
    is_nominated, is_active, created_at, updated_at
FROM masca_bank_accounts
ON CONFLICT (account_number, currency_code) DO NOTHING;

-- 3. Add source_bank_account_id on payment_runs (nullable first for backfill).
ALTER TABLE payment_runs
    ADD COLUMN IF NOT EXISTS source_bank_account_id UUID
        REFERENCES tenant_bank_accounts(id);

-- 4. Backfill: nominated-per-currency first, then any-active per currency,
--    then RAISE for any run still null.
UPDATE payment_runs pr
   SET source_bank_account_id = tba.id
  FROM tenant_bank_accounts tba
 WHERE tba.currency_code = pr.currency_code
   AND tba.is_nominated  = TRUE
   AND pr.source_bank_account_id IS NULL;

UPDATE payment_runs pr
   SET source_bank_account_id = tba.id
  FROM tenant_bank_accounts tba
 WHERE tba.currency_code = pr.currency_code
   AND tba.is_active     = TRUE
   AND pr.source_bank_account_id IS NULL
   AND tba.id = (
       SELECT id FROM tenant_bank_accounts x
        WHERE x.currency_code = pr.currency_code
          AND x.is_active     = TRUE
        LIMIT 1
   );

DO $$
DECLARE
    orphan_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO orphan_count
      FROM payment_runs
     WHERE source_bank_account_id IS NULL;
    IF orphan_count > 0 THEN
        RAISE EXCEPTION 'V075 backfill failed: % payment_run row(s) have no eligible bank account for their currency. '
                        'Add an active tenant_bank_account for every currency in use before deploying.',
                        orphan_count;
    END IF;
END $$;

-- 5. Lock the column NOT NULL now that every existing row has an id.
ALTER TABLE payment_runs
    ALTER COLUMN source_bank_account_id SET NOT NULL;

-- 6. Permission swap. Auto-grant admin.bank_accounts:manage to tenant_admin
--    only (V073 precedent); other roles that had finance:manage_banks are
--    dropped and must be re-granted manually.
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), r.id, 'admin.bank_accounts:manage', 'full'
  FROM roles r
 WHERE r.name = 'tenant_admin'
ON CONFLICT (role_id, permission) DO NOTHING;

DELETE FROM role_permissions WHERE permission = 'finance:manage_banks';

-- 7. Drop the old table now that data + FKs are cut across.
DROP TABLE IF EXISTS masca_bank_accounts;
