-- =====================================================================
-- V128: Per-tenant advance-payment approval threshold
-- =====================================================================
-- One row per tenant. Advances at or below the threshold auto-approve on
-- record; above it, they stay status='pending' until a second operator
-- approves. The threshold is expressed in a chosen currency; FX conversion
-- to the advance's currency happens at record-time using the shared
-- exchange-rate table (public.exchange_rates).
--
-- Absent row falls through to platform default of 500 USD (matching the
-- column defaults below).
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.tenant_advance_payment_config (
    tenant_id                    UUID           PRIMARY KEY REFERENCES public.tenants(id) ON DELETE CASCADE,
    approval_threshold_amount    NUMERIC(19, 4) NOT NULL DEFAULT 500,
    approval_threshold_currency  VARCHAR(3)     NOT NULL DEFAULT 'USD',
    updated_at                   TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by                   UUID
);

COMMENT ON TABLE public.tenant_advance_payment_config IS
    'Per-tenant approval threshold for AdvancePayment.create. Absent row = defaults (500 USD).';

-- Seed every existing tenant with the default. Idempotent for reruns.
INSERT INTO public.tenant_advance_payment_config (tenant_id)
    SELECT id FROM public.tenants
    ON CONFLICT (tenant_id) DO NOTHING;
