-- Extend tenant_currency_config with the scope flags and rate-source field
-- defined in .claude/multi-currency.md. Existing rows (the per-tenant default
-- seeded at provisioning time) are backfilled with all scope flags TRUE and
-- the manual rate source so live tenants keep working; tenant admins can
-- refine via the UI later.

ALTER TABLE public.tenant_currency_config
    ADD COLUMN IF NOT EXISTS is_billing_currency  BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_claims_currency   BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS is_payment_currency  BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS exchange_rate_source VARCHAR(50) NOT NULL DEFAULT 'manual';

UPDATE public.tenant_currency_config
SET is_billing_currency = TRUE,
    is_claims_currency  = TRUE,
    is_payment_currency = TRUE
WHERE is_active = TRUE
  AND is_billing_currency = FALSE
  AND is_claims_currency  = FALSE
  AND is_payment_currency = FALSE;

-- Tie currency_code to the master registry. Run this AFTER V111 has seeded
-- the codes so existing rows are valid. Defer the FK so tests don't break
-- on transient state.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_tenant_currency_config_currency'
    ) THEN
        ALTER TABLE public.tenant_currency_config
            ADD CONSTRAINT fk_tenant_currency_config_currency
            FOREIGN KEY (currency_code) REFERENCES public.currencies(code);
    END IF;
END$$;

-- Exactly one default currency per tenant.
CREATE UNIQUE INDEX IF NOT EXISTS uq_tenant_currency_config_default
    ON public.tenant_currency_config (tenant_id) WHERE is_default = TRUE;
