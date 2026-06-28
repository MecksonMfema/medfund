-- =====================================================================
-- V118: Tenant-level pricing-mode toggle
-- =====================================================================
-- Per-member pricing overrides (V030 / billing_override_amount) ship
-- alongside the scheme-default pricing — both are valid models. A
-- tenant picks one via this column. Naming is deliberately line-
-- agnostic: STANDARD means "use whatever the scheme's insurance line
-- normally prices off", and what that is differs by line. For HEALTH
-- it's age_groups; for LIFE it'd be sum-assured bands; for VEHICLE
-- it's asset-based premiums; etc. INDIVIDUAL is the same shape across
-- every line — per-member override.
--
--   STANDARD   (default)  — every member is priced from the scheme-
--                            default source for its insurance line.
--                            Per-member overrides on the row are
--                            ignored by the billing run. Matches what
--                            every tenant had before V030.
--
--   INDIVIDUAL            — per-member billing_override_amount wins
--                            when set + effective; otherwise the
--                            scheme-default price acts as the fallback.
--                            Lets the tenant phase in individual
--                            pricing without having to set an
--                            override on every member at once.
--
-- Default of STANDARD guarantees a no-op upgrade for existing tenants:
-- their billing keeps producing the same numbers it did before V030
-- until they explicitly opt in via the tenant admin UI.
-- =====================================================================

ALTER TABLE public.tenants
    ADD COLUMN IF NOT EXISTS pricing_model VARCHAR(20) NOT NULL DEFAULT 'STANDARD'
        CHECK (pricing_model IN ('STANDARD', 'INDIVIDUAL'));
