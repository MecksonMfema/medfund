-- =====================================================================
-- V099: PaymentRun payee_type widening + treaty.producer_id + perms seed
-- =====================================================================
-- Three-part migration (Phase 11 §A/§B foundation):
--   Part 1 — widen payment_runs / payments / payment_run_items /
--            payment_advices to accept payee_type='PRODUCER'. The V072
--            item-parent trigger reads parent payee_type at runtime and
--            does not need edits (F11-f).
--   Part 2 — add treaty.producer_id FK (Phase 10 §B backfills; this
--            migration only adds the column so the FK target exists).
--   Part 3 — seed the 11 finance.producer:* + finance.commission:* +
--            tenant.settings:manage_auto_lapse permission keys.
--
-- All ALTERs are idempotent (IF NOT EXISTS / DROP IF EXISTS then ADD).
-- =====================================================================

-- ── Part 1: PaymentRun payee_type widening (F11-f — trigger unchanged) ──────

ALTER TABLE payment_runs
    DROP CONSTRAINT IF EXISTS payment_runs_payee_type_check;
ALTER TABLE payment_runs
    ADD CONSTRAINT payment_runs_payee_type_check
        CHECK (payee_type IN ('PROVIDER','MEMBER','PRODUCER'));

ALTER TABLE payments
    ADD COLUMN IF NOT EXISTS producer_id UUID REFERENCES producer(id) ON DELETE RESTRICT;
ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS payments_payee_type_check;
ALTER TABLE payments
    ADD CONSTRAINT payments_payee_type_check
        CHECK (payee_type IN ('PROVIDER','MEMBER','PRODUCER'));
ALTER TABLE payments
    DROP CONSTRAINT IF EXISTS payments_payee_xor;
ALTER TABLE payments
    ADD CONSTRAINT payments_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL     AND producer_id IS NULL     AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL     AND member_id IS NOT NULL AND producer_id IS NULL     AND payee_type = 'MEMBER')
            OR (provider_id IS NULL     AND member_id IS NULL     AND producer_id IS NOT NULL AND payee_type = 'PRODUCER'));
CREATE INDEX IF NOT EXISTS ix_payments_producer ON payments (producer_id) WHERE producer_id IS NOT NULL;

ALTER TABLE payment_run_items
    ADD COLUMN IF NOT EXISTS producer_id         UUID          REFERENCES producer(id) ON DELETE RESTRICT,
    ADD COLUMN IF NOT EXISTS withholding_tax_pct NUMERIC(5,2);
ALTER TABLE payment_run_items
    DROP CONSTRAINT IF EXISTS payment_run_items_payee_type_check;
ALTER TABLE payment_run_items
    ADD CONSTRAINT payment_run_items_payee_type_check
        CHECK (payee_type IN ('PROVIDER','MEMBER','PRODUCER'));
ALTER TABLE payment_run_items
    DROP CONSTRAINT IF EXISTS payment_run_items_payee_xor;
ALTER TABLE payment_run_items
    ADD CONSTRAINT payment_run_items_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL     AND producer_id IS NULL     AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL     AND member_id IS NOT NULL AND producer_id IS NULL     AND payee_type = 'MEMBER')
            OR (provider_id IS NULL     AND member_id IS NULL     AND producer_id IS NOT NULL AND payee_type = 'PRODUCER'));
ALTER TABLE payment_run_items
    DROP CONSTRAINT IF EXISTS payment_run_items_wht_range_ck;
ALTER TABLE payment_run_items
    ADD CONSTRAINT payment_run_items_wht_range_ck
        CHECK (withholding_tax_pct IS NULL
               OR (withholding_tax_pct >= 0 AND withholding_tax_pct <= 100));
CREATE INDEX IF NOT EXISTS ix_payment_run_items_producer
    ON payment_run_items (producer_id) WHERE producer_id IS NOT NULL;

ALTER TABLE payment_advices
    ADD COLUMN IF NOT EXISTS producer_id UUID REFERENCES producer(id) ON DELETE RESTRICT;
ALTER TABLE payment_advices
    DROP CONSTRAINT IF EXISTS payment_advices_payee_type_check;
ALTER TABLE payment_advices
    ADD CONSTRAINT payment_advices_payee_type_check
        CHECK (payee_type IN ('PROVIDER','MEMBER','PRODUCER'));
ALTER TABLE payment_advices
    DROP CONSTRAINT IF EXISTS payment_advices_payee_xor;
ALTER TABLE payment_advices
    ADD CONSTRAINT payment_advices_payee_xor
        CHECK ((provider_id IS NOT NULL AND member_id IS NULL     AND producer_id IS NULL     AND payee_type = 'PROVIDER')
            OR (provider_id IS NULL     AND member_id IS NOT NULL AND producer_id IS NULL     AND payee_type = 'MEMBER')
            OR (provider_id IS NULL     AND member_id IS NULL     AND producer_id IS NOT NULL AND payee_type = 'PRODUCER'));

-- ── Part 2: treaty.producer_id FK (backfilled by Phase 10 §B) ────────────

ALTER TABLE treaty
    ADD COLUMN IF NOT EXISTS producer_id UUID REFERENCES producer(id) ON DELETE RESTRICT;
CREATE INDEX IF NOT EXISTS ix_treaty_producer_id ON treaty (producer_id)
    WHERE producer_id IS NOT NULL;

-- ── Part 3: role_permissions seed for producer / commission / auto-lapse ─
-- Follows V091 shape exactly — the catalogue itself lives in
-- Permissions.java + permissions.yaml + permissions.ts; this migration
-- grants the 11 new keys to the built-in seed roles so the surface is
-- usable out of the box. Tenants carve narrower audiences via the role
-- editor. Idempotent via ON CONFLICT (role_id, permission).
--
--   * tenant_admin     → every key (10 finance.* + 1 tenant.settings.*).
--   * finance_officer  → view-only entry points: finance.producer:view +
--                        finance.commission:view. Higher-privilege ops
--                        (manage, terminate, draft/approve, payout runs,
--                        backfill review, auto-lapse settings) are opt-in
--                        via tenant-defined roles.

WITH admin_role AS (
    SELECT id FROM roles WHERE name = 'tenant_admin'
), admin_perms(permission) AS (
    VALUES
        ('finance.producer:view'),
        ('finance.producer:manage'),
        ('finance.producer:terminate'),
        ('finance.producer:backfill_review'),
        ('finance.commission:view'),
        ('finance.commission:manage_rate_card'),
        ('finance.commission:draft_adjustment'),
        ('finance.commission:approve_adjustment'),
        ('finance.commission:create_payout_run'),
        ('finance.commission:approve_payout_run'),
        ('tenant.settings:manage_auto_lapse')
)
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), admin_role.id, admin_perms.permission, 'full'
  FROM admin_role, admin_perms
ON CONFLICT (role_id, permission) DO NOTHING;

WITH officer_role AS (
    SELECT id FROM roles WHERE name = 'finance_officer'
), officer_perms(permission) AS (
    VALUES
        ('finance.producer:view'),
        ('finance.commission:view')
)
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), officer_role.id, officer_perms.permission, 'full'
  FROM officer_role, officer_perms
ON CONFLICT (role_id, permission) DO NOTHING;
