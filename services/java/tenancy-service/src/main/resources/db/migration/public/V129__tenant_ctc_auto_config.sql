-- =====================================================================
-- V129: Per-tenant auto-CTC configuration.
--
-- Auto-CTC auto-drafts a Claims-to-Contributions transfer when a
-- MEMBER-payee claim adjudicates APPROVED / PARTIAL_APPROVED and the
-- member's outstanding contribution balance is above the configured
-- threshold. Never auto-commits: the draft still needs operator review
-- (per Phase 4 design decision — operator/member consent for the offset
-- stays mandatory even when the rule is deterministic).
--
-- Threshold is stored in a chosen currency; FX conversion to the
-- member's contribution-ledger currency happens at evaluation time
-- (finance-service side) via ExchangeRateProvider.
--
-- Absent row = disabled for that tenant (safe default — auto-drafting
-- stays off until the tenant admin opts in).
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.tenant_ctc_auto_config (
    tenant_id                     UUID           PRIMARY KEY REFERENCES public.tenants(id) ON DELETE CASCADE,
    enabled                       BOOLEAN        NOT NULL DEFAULT FALSE,
    min_member_balance_threshold  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    max_per_ctc_amount            NUMERIC(19, 4),   -- NULL = no per-CTC cap
    threshold_currency            VARCHAR(3)     NOT NULL DEFAULT 'USD',
    updated_at                    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    updated_by                    UUID
);

COMMENT ON TABLE public.tenant_ctc_auto_config IS
    'Per-tenant auto-CTC configuration. Absent row or enabled=false = feature off.';

-- Seed every existing tenant with disabled defaults. Idempotent for reruns.
INSERT INTO public.tenant_ctc_auto_config (tenant_id)
    SELECT id FROM public.tenants
    ON CONFLICT (tenant_id) DO NOTHING;
