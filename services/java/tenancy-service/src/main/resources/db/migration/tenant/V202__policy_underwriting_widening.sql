-- ── Phase 12 §A: policy schema-widening for the premium module ────────
-- Adds the columns Phase 12 needs to capture written premium at bind
-- time, the coverage window, the renewal chain, and the IFRS 17
-- portfolio / cohort dimensions on every policy entity plus HEALTH's
-- Contribution row. Existing rows get a LEGACY_NO_PREMIUM status arm so
-- the PremiumEarningExecutor skips them until the tenant admin
-- retrofits each one via the legacy-retrofit screen.
--
-- Constraint drops target the auto-generated Postgres name
-- `<table>_status_check` from V032's inline CHECK — the plan's
-- `chk_<table>_status` name would silently no-op via IF EXISTS and
-- leave the original constraint rejecting the new status arms.
-- Portfolio / cohort FKs land in V107 / V108 after those tables exist.

-- ── LifePolicy ─────────────────────────────────────────────────────────
ALTER TABLE life_policies
    ADD COLUMN IF NOT EXISTS written_premium NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS written_premium_currency CHAR(3),
    ADD COLUMN IF NOT EXISTS bound_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS coverage_start DATE,
    ADD COLUMN IF NOT EXISTS coverage_end DATE,
    ADD COLUMN IF NOT EXISTS renewed_from_policy_id UUID
        REFERENCES life_policies(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS portfolio_id UUID,
    ADD COLUMN IF NOT EXISTS cohort_id UUID;

ALTER TABLE life_policies DROP CONSTRAINT IF EXISTS life_policies_status_check;
ALTER TABLE life_policies ADD CONSTRAINT life_policies_status_check
    CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium'));

UPDATE life_policies SET
    bound_at       = COALESCE(bound_at, created_at),
    coverage_start = COALESCE(coverage_start, created_at::DATE),
    coverage_end   = COALESCE(coverage_end, (created_at + INTERVAL '1 year')::DATE),
    status         = CASE WHEN written_premium IS NULL THEN 'legacy_no_premium' ELSE status END
WHERE bound_at IS NULL OR coverage_start IS NULL OR coverage_end IS NULL;

-- ── FuneralPolicy ──────────────────────────────────────────────────────
ALTER TABLE funeral_policies
    ADD COLUMN IF NOT EXISTS written_premium NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS written_premium_currency CHAR(3),
    ADD COLUMN IF NOT EXISTS bound_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS coverage_start DATE,
    ADD COLUMN IF NOT EXISTS coverage_end DATE,
    ADD COLUMN IF NOT EXISTS renewed_from_policy_id UUID
        REFERENCES funeral_policies(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS portfolio_id UUID,
    ADD COLUMN IF NOT EXISTS cohort_id UUID;

ALTER TABLE funeral_policies DROP CONSTRAINT IF EXISTS funeral_policies_status_check;
ALTER TABLE funeral_policies ADD CONSTRAINT funeral_policies_status_check
    CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium'));

UPDATE funeral_policies SET
    bound_at       = COALESCE(bound_at, created_at),
    coverage_start = COALESCE(coverage_start, created_at::DATE),
    coverage_end   = COALESCE(coverage_end, (created_at + INTERVAL '1 year')::DATE),
    status         = CASE WHEN written_premium IS NULL THEN 'legacy_no_premium' ELSE status END
WHERE bound_at IS NULL OR coverage_start IS NULL OR coverage_end IS NULL;

-- ── DisabilityPolicy ───────────────────────────────────────────────────
ALTER TABLE disability_policies
    ADD COLUMN IF NOT EXISTS written_premium NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS written_premium_currency CHAR(3),
    ADD COLUMN IF NOT EXISTS bound_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS coverage_start DATE,
    ADD COLUMN IF NOT EXISTS coverage_end DATE,
    ADD COLUMN IF NOT EXISTS renewed_from_policy_id UUID
        REFERENCES disability_policies(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS portfolio_id UUID,
    ADD COLUMN IF NOT EXISTS cohort_id UUID;

ALTER TABLE disability_policies DROP CONSTRAINT IF EXISTS disability_policies_status_check;
ALTER TABLE disability_policies ADD CONSTRAINT disability_policies_status_check
    CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium'));

UPDATE disability_policies SET
    bound_at       = COALESCE(bound_at, created_at),
    coverage_start = COALESCE(coverage_start, created_at::DATE),
    coverage_end   = COALESCE(coverage_end, (created_at + INTERVAL '1 year')::DATE),
    status         = CASE WHEN written_premium IS NULL THEN 'legacy_no_premium' ELSE status END
WHERE bound_at IS NULL OR coverage_start IS NULL OR coverage_end IS NULL;

-- ── TravelPolicy (special case — trip_start_date / trip_end_date already carry the window) ──
ALTER TABLE travel_policies
    ADD COLUMN IF NOT EXISTS written_premium NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS written_premium_currency CHAR(3),
    ADD COLUMN IF NOT EXISTS bound_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS renewed_from_policy_id UUID
        REFERENCES travel_policies(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS portfolio_id UUID,
    ADD COLUMN IF NOT EXISTS cohort_id UUID;

ALTER TABLE travel_policies DROP CONSTRAINT IF EXISTS travel_policies_status_check;
ALTER TABLE travel_policies ADD CONSTRAINT travel_policies_status_check
    CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium'));

UPDATE travel_policies SET
    bound_at = COALESCE(bound_at, created_at),
    status   = CASE WHEN written_premium IS NULL THEN 'legacy_no_premium' ELSE status END
WHERE bound_at IS NULL;

-- ── Vehicles (MOTOR) ───────────────────────────────────────────────────
ALTER TABLE vehicles
    ADD COLUMN IF NOT EXISTS written_premium NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS written_premium_currency CHAR(3),
    ADD COLUMN IF NOT EXISTS bound_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS coverage_start DATE,
    ADD COLUMN IF NOT EXISTS coverage_end DATE,
    ADD COLUMN IF NOT EXISTS renewed_from_policy_id UUID
        REFERENCES vehicles(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS portfolio_id UUID,
    ADD COLUMN IF NOT EXISTS cohort_id UUID;

ALTER TABLE vehicles DROP CONSTRAINT IF EXISTS vehicles_status_check;
ALTER TABLE vehicles ADD CONSTRAINT vehicles_status_check
    CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium'));

UPDATE vehicles SET
    bound_at       = COALESCE(bound_at, created_at),
    coverage_start = COALESCE(coverage_start, created_at::DATE),
    coverage_end   = COALESCE(coverage_end, (created_at + INTERVAL '1 year')::DATE),
    status         = CASE WHEN written_premium IS NULL THEN 'legacy_no_premium' ELSE status END
WHERE bound_at IS NULL OR coverage_start IS NULL OR coverage_end IS NULL;

-- ── Properties ─────────────────────────────────────────────────────────
ALTER TABLE properties
    ADD COLUMN IF NOT EXISTS written_premium NUMERIC(19, 4),
    ADD COLUMN IF NOT EXISTS written_premium_currency CHAR(3),
    ADD COLUMN IF NOT EXISTS bound_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS coverage_start DATE,
    ADD COLUMN IF NOT EXISTS coverage_end DATE,
    ADD COLUMN IF NOT EXISTS renewed_from_policy_id UUID
        REFERENCES properties(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS portfolio_id UUID,
    ADD COLUMN IF NOT EXISTS cohort_id UUID;

ALTER TABLE properties DROP CONSTRAINT IF EXISTS properties_status_check;
ALTER TABLE properties ADD CONSTRAINT properties_status_check
    CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium'));

UPDATE properties SET
    bound_at       = COALESCE(bound_at, created_at),
    coverage_start = COALESCE(coverage_start, created_at::DATE),
    coverage_end   = COALESCE(coverage_end, (created_at + INTERVAL '1 year')::DATE),
    status         = CASE WHEN written_premium IS NULL THEN 'legacy_no_premium' ELSE status END
WHERE bound_at IS NULL OR coverage_start IS NULL OR coverage_end IS NULL;

-- ── Contribution (HEALTH) additive: portfolio + cohort only ───────────
ALTER TABLE contributions
    ADD COLUMN IF NOT EXISTS portfolio_id UUID,
    ADD COLUMN IF NOT EXISTS cohort_id UUID;

-- ── schemes.default_portfolio_id (grill note 12 — HEALTH billing default) ──
ALTER TABLE schemes
    ADD COLUMN IF NOT EXISTS default_portfolio_id UUID;

-- ── Indexes for period-scan queries (used by the earning executor + reports) ──
CREATE INDEX IF NOT EXISTS ix_life_bound_at
    ON life_policies (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_life_coverage_end
    ON life_policies (coverage_end) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS ix_funeral_bound_at
    ON funeral_policies (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_funeral_coverage_end
    ON funeral_policies (coverage_end) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS ix_disability_bound_at
    ON disability_policies (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_disability_coverage_end
    ON disability_policies (coverage_end) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS ix_travel_bound_at
    ON travel_policies (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_travel_trip_end
    ON travel_policies (trip_end_date) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS ix_vehicle_bound_at
    ON vehicles (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_vehicle_coverage_end
    ON vehicles (coverage_end) WHERE status = 'active';
CREATE INDEX IF NOT EXISTS ix_property_bound_at
    ON properties (bound_at) WHERE bound_at IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_property_coverage_end
    ON properties (coverage_end) WHERE status = 'active';
