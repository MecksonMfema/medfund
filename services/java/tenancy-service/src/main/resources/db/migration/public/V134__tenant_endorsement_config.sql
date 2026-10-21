-- =====================================================================
-- V134: Per-tenant endorsement configuration (Phase 12 §C)
-- =====================================================================
-- Single-row-per-tenant config for the four-eyes endorsement threshold.
-- Absent row (or enabled=false) => endorsements auto-commit without
-- four-eyes; PolicyEndorsementService.createDraft skips the DRAFT stage.
-- Enabled + threshold => a premium_delta ≥ threshold_amount (in
-- threshold_currency) drops into DRAFT and requires a second actor to
-- approve + commit. Follows V133 shape verbatim.
-- =====================================================================

CREATE TABLE IF NOT EXISTS public.tenant_endorsement_config (
    id                          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                   UUID         NOT NULL REFERENCES public.tenants(id) ON DELETE CASCADE,
    enabled                     BOOLEAN      NOT NULL DEFAULT FALSE,
    four_eyes_threshold_amount  NUMERIC(19, 4),
    threshold_currency          CHAR(3),
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id                    UUID,
    actor_email                 VARCHAR(255),
    CONSTRAINT uq_tenant_endorsement_config UNIQUE (tenant_id),
    CONSTRAINT chk_endorsement_threshold_paired CHECK (
        (four_eyes_threshold_amount IS NULL AND threshold_currency IS NULL)
        OR (four_eyes_threshold_amount IS NOT NULL AND threshold_currency IS NOT NULL)
    ),
    CONSTRAINT chk_endorsement_threshold_positive CHECK (
        four_eyes_threshold_amount IS NULL OR four_eyes_threshold_amount >= 0
    )
);

COMMENT ON TABLE  public.tenant_endorsement_config IS
    'Per-tenant endorsement four-eyes configuration (Phase 12 §C). When enabled and a premium_delta reaches four_eyes_threshold_amount (in threshold_currency), PolicyEndorsementService.createDraft parks the endorsement at DRAFT and requires a second actor to approve/commit. Absent row or enabled=false auto-commits at create time.';
