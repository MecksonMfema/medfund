-- =====================================================================
-- V133: Per-tenant auto-lapse configuration (Phase 11 §B P7)
-- =====================================================================
-- Single-row-per-tenant config for the auto-lapse chain: when a member
-- crosses arrears_threshold_months, the arrears-threshold-breached event
-- schedules a Member.scheduledStatus='LAPSED' transition after
-- grace_window_days. Deliberately NOT seeded for existing tenants — the
-- ArrearsBreachedConsumer short-circuits when enabled=false (or the row
-- is absent). Follow V132 shape.
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.tenant_auto_lapse_config (
    id                        UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                 UUID         NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    enabled                   BOOLEAN      NOT NULL DEFAULT FALSE,
    arrears_threshold_months  INT,
    grace_window_days         INT,
    created_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id                  UUID,
    actor_email               VARCHAR(255),
    CONSTRAINT uq_tenant_auto_lapse_config UNIQUE (tenant_id),
    CONSTRAINT ck_auto_lapse_threshold CHECK (arrears_threshold_months IS NULL
                                              OR arrears_threshold_months BETWEEN 1 AND 60),
    CONSTRAINT ck_auto_lapse_grace     CHECK (grace_window_days IS NULL
                                              OR grace_window_days BETWEEN 0 AND 180)
);

COMMENT ON TABLE  public.tenant_auto_lapse_config IS
    'Per-tenant auto-lapse chain configuration. When enabled, ArrearsEscalationExecutor emits medfund.contributions.arrears-threshold-breached at arrears_threshold_months; user-service ArrearsBreachedConsumer sets Member.scheduledStatus=LAPSED with scheduledStatusEffectiveFrom = today + grace_window_days.';
