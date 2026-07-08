-- =====================================================================
-- V127: Per-tenant benefit-limit proration configuration
-- =====================================================================
-- One row per tenant. Absent row = platform default = NONE (preserves
-- pre-feature adjudication behaviour). Values reference the seven baked-in
-- ProrationStrategy enum values in rules-engine:
--   NONE
--   DELTA_CREDIT
--   RATIO_CARRY
--   CALENDAR
--   SPLIT_YEAR
--   WAITING_PERIOD_ON_INCREMENT
--   HYBRID_BY_DIRECTION
--
-- The per-direction columns (upgrade/downgrade/currency) are optional
-- overrides used when default_strategy = HYBRID_BY_DIRECTION. Nulls fall
-- through to default_strategy.
--
-- increment_wait_days is only consulted when the effective strategy is
-- WAITING_PERIOD_ON_INCREMENT. Nullable; a null there means "no wait".
--
-- Resolution order at claim time (claims-service/ProrationService):
--   1. BENEFIT_PRORATION rule sets ClaimFact.prorationStrategy
--   2. This config row (direction-aware)
--   3. NONE
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.tenant_proration_config (
    tenant_id           UUID        PRIMARY KEY REFERENCES public.tenants(id) ON DELETE CASCADE,
    default_strategy    VARCHAR(48) NOT NULL DEFAULT 'NONE'
        CHECK (default_strategy IN (
            'NONE', 'DELTA_CREDIT', 'RATIO_CARRY', 'CALENDAR',
            'SPLIT_YEAR', 'WAITING_PERIOD_ON_INCREMENT', 'HYBRID_BY_DIRECTION')),
    upgrade_strategy    VARCHAR(48)
        CHECK (upgrade_strategy IS NULL OR upgrade_strategy IN (
            'NONE', 'DELTA_CREDIT', 'RATIO_CARRY', 'CALENDAR',
            'SPLIT_YEAR', 'WAITING_PERIOD_ON_INCREMENT')),
    downgrade_strategy  VARCHAR(48)
        CHECK (downgrade_strategy IS NULL OR downgrade_strategy IN (
            'NONE', 'DELTA_CREDIT', 'RATIO_CARRY', 'CALENDAR',
            'SPLIT_YEAR', 'WAITING_PERIOD_ON_INCREMENT')),
    currency_strategy   VARCHAR(48)
        CHECK (currency_strategy IS NULL OR currency_strategy IN (
            'NONE', 'DELTA_CREDIT', 'RATIO_CARRY', 'CALENDAR',
            'SPLIT_YEAR', 'WAITING_PERIOD_ON_INCREMENT')),
    increment_wait_days INTEGER
        CHECK (increment_wait_days IS NULL OR increment_wait_days >= 0),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by          UUID
);

COMMENT ON TABLE public.tenant_proration_config IS
    'Per-tenant benefit-limit proration strategy on scheme change. Absent row = NONE.';
