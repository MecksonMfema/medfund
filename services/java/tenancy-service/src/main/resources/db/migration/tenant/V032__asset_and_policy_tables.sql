-- =====================================================================
-- V032: Asset + policy tables for the six non-HEALTH insurance lines
-- =====================================================================
-- The platform's billing engine (Part 1 of the multi-line plan)
-- dispatches CandidateResolver implementations by insurance_line.
-- Until those tables exist, only HEALTH can bill. This migration
-- adds the entities the other six lines bill against:
--
--   MOTOR        vehicles            (asset-centric, owner_member_id nullable)
--   PROPERTY     properties          (asset-centric, owner_member_id nullable)
--   LIFE         life_policies       (person-insuring, insured_member_id NOT NULL)
--   FUNERAL      funeral_policies    (person-insuring, principal_member_id NOT NULL)
--   TRAVEL       travel_policies     (person-insuring, traveler_member_id NOT NULL)
--   DISABILITY   disability_policies (person-insuring, insured_member_id NOT NULL)
--
-- Every table has:
--   - id UUID PK (gen_random_uuid default)
--   - scheme_id FK so billing can group by scheme
--   - group_id  FK (NULL) for group-routed invoicing
--   - a friendly-name column (registration_number / property_name /
--     policy_number) that satisfies [[feedback_audit_entity_name]] —
--     audit rows show this not the UUID
--   - status with the active/suspended/terminated CHECK pattern
--   - audit columns (created_at, updated_at, created_by, updated_by)
--   - billing_override_amount + billing_override_reason +
--     billing_override_effective_from so INDIVIDUAL pricing mode
--     works uniformly across lines (V030 added these on members /
--     dependants only)
--
-- Person-insuring policies (life / funeral / travel / disability) all
-- take a NOT NULL FK to members.id — a member is the single source
-- of truth for insured-person identity. Asset-centric (vehicles,
-- properties) carry an owner_member_id but allow NULL so a corporate
-- portfolio without per-asset owner identification still works.
-- =====================================================================

-- ── MOTOR / vehicles ──────────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS vehicles (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id),
    group_id            UUID NULL  REFERENCES groups(id),
    owner_member_id     UUID NULL  REFERENCES members(id),

    registration_number VARCHAR(32)  NOT NULL,
    make                VARCHAR(60)  NOT NULL,
    model               VARCHAR(60)  NOT NULL,
    year                INTEGER      NOT NULL CHECK (year BETWEEN 1900 AND 2100),
    vehicle_value       NUMERIC(19,4) NOT NULL CHECK (vehicle_value >= 0),
    body_type           VARCHAR(40)  NULL,
    usage_type          VARCHAR(40)  NULL,

    status              VARCHAR(20)  NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','suspended','terminated')),

    billing_override_amount NUMERIC(19,4) NULL CHECK (billing_override_amount IS NULL OR billing_override_amount > 0),
    billing_override_reason VARCHAR(40)   NULL,
    billing_override_effective_from DATE  NULL,
    CONSTRAINT vehicles_override_requires_effective_from
        CHECK (billing_override_amount IS NULL OR billing_override_effective_from IS NOT NULL),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID NULL,
    updated_by UUID NULL,

    CONSTRAINT vehicles_registration_unique UNIQUE (registration_number)
);
CREATE INDEX IF NOT EXISTS idx_vehicles_scheme  ON vehicles(scheme_id);
CREATE INDEX IF NOT EXISTS idx_vehicles_group   ON vehicles(group_id);
CREATE INDEX IF NOT EXISTS idx_vehicles_owner   ON vehicles(owner_member_id);
CREATE INDEX IF NOT EXISTS idx_vehicles_status  ON vehicles(status);

-- ── PROPERTY / properties ────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS properties (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id),
    group_id            UUID NULL  REFERENCES groups(id),
    owner_member_id     UUID NULL  REFERENCES members(id),

    property_name       VARCHAR(200) NOT NULL,
    address             TEXT         NOT NULL,
    sum_insured         NUMERIC(19,4) NOT NULL CHECK (sum_insured >= 0),
    construction_type   VARCHAR(40)  NULL,
    roof_type           VARCHAR(40)  NULL,
    location_risk_band  VARCHAR(20)  NULL CHECK (location_risk_band IS NULL OR location_risk_band IN ('LOW','MEDIUM','HIGH')),
    security_features_count INTEGER  NOT NULL DEFAULT 0 CHECK (security_features_count >= 0),
    property_age_years  INTEGER      NULL CHECK (property_age_years IS NULL OR property_age_years >= 0),
    occupancy           VARCHAR(20)  NULL CHECK (occupancy IS NULL OR occupancy IN ('OWNER','TENANT','VACANT')),

    status              VARCHAR(20)  NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','suspended','terminated')),

    billing_override_amount NUMERIC(19,4) NULL CHECK (billing_override_amount IS NULL OR billing_override_amount > 0),
    billing_override_reason VARCHAR(40)   NULL,
    billing_override_effective_from DATE  NULL,
    CONSTRAINT properties_override_requires_effective_from
        CHECK (billing_override_amount IS NULL OR billing_override_effective_from IS NOT NULL),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID NULL,
    updated_by UUID NULL
);
CREATE INDEX IF NOT EXISTS idx_properties_scheme ON properties(scheme_id);
CREATE INDEX IF NOT EXISTS idx_properties_group  ON properties(group_id);
CREATE INDEX IF NOT EXISTS idx_properties_owner  ON properties(owner_member_id);
CREATE INDEX IF NOT EXISTS idx_properties_status ON properties(status);
CREATE INDEX IF NOT EXISTS idx_properties_name_lower ON properties(lower(property_name));

-- ── LIFE / life_policies ─────────────────────────────────────────────

CREATE TABLE IF NOT EXISTS life_policies (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id            UUID NOT NULL REFERENCES schemes(id),
    group_id             UUID NULL  REFERENCES groups(id),
    insured_member_id    UUID NOT NULL REFERENCES members(id),

    policy_number        VARCHAR(50)  NOT NULL,
    sum_assured          NUMERIC(19,4) NOT NULL CHECK (sum_assured >= 0),
    occupation_hazard_class VARCHAR(20) NULL
                         CHECK (occupation_hazard_class IS NULL OR
                                occupation_hazard_class IN ('SEDENTARY','MANUAL','HAZARDOUS','VERY_HAZARDOUS')),
    term_months          INTEGER      NOT NULL CHECK (term_months > 0),

    status               VARCHAR(20)  NOT NULL DEFAULT 'active'
                         CHECK (status IN ('active','suspended','terminated')),

    billing_override_amount NUMERIC(19,4) NULL CHECK (billing_override_amount IS NULL OR billing_override_amount > 0),
    billing_override_reason VARCHAR(40)   NULL,
    billing_override_effective_from DATE  NULL,
    CONSTRAINT life_policies_override_requires_effective_from
        CHECK (billing_override_amount IS NULL OR billing_override_effective_from IS NOT NULL),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID NULL,
    updated_by UUID NULL,

    CONSTRAINT life_policies_policy_number_unique UNIQUE (policy_number)
);
CREATE INDEX IF NOT EXISTS idx_life_policies_scheme ON life_policies(scheme_id);
CREATE INDEX IF NOT EXISTS idx_life_policies_group  ON life_policies(group_id);
CREATE INDEX IF NOT EXISTS idx_life_policies_member ON life_policies(insured_member_id);
CREATE INDEX IF NOT EXISTS idx_life_policies_status ON life_policies(status);

-- ── FUNERAL / funeral_policies ───────────────────────────────────────

CREATE TABLE IF NOT EXISTS funeral_policies (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id            UUID NOT NULL REFERENCES schemes(id),
    group_id             UUID NULL  REFERENCES groups(id),
    principal_member_id  UUID NOT NULL REFERENCES members(id),

    policy_number        VARCHAR(50)  NOT NULL,
    cover_amount         NUMERIC(19,4) NOT NULL CHECK (cover_amount >= 0),
    lives_covered        INTEGER      NOT NULL DEFAULT 1 CHECK (lives_covered >= 1),
    health_declaration   JSONB        NULL,

    status               VARCHAR(20)  NOT NULL DEFAULT 'active'
                         CHECK (status IN ('active','suspended','terminated')),

    billing_override_amount NUMERIC(19,4) NULL CHECK (billing_override_amount IS NULL OR billing_override_amount > 0),
    billing_override_reason VARCHAR(40)   NULL,
    billing_override_effective_from DATE  NULL,
    CONSTRAINT funeral_policies_override_requires_effective_from
        CHECK (billing_override_amount IS NULL OR billing_override_effective_from IS NOT NULL),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID NULL,
    updated_by UUID NULL,

    CONSTRAINT funeral_policies_policy_number_unique UNIQUE (policy_number)
);
CREATE INDEX IF NOT EXISTS idx_funeral_policies_scheme    ON funeral_policies(scheme_id);
CREATE INDEX IF NOT EXISTS idx_funeral_policies_group     ON funeral_policies(group_id);
CREATE INDEX IF NOT EXISTS idx_funeral_policies_principal ON funeral_policies(principal_member_id);
CREATE INDEX IF NOT EXISTS idx_funeral_policies_status    ON funeral_policies(status);

-- ── TRAVEL / travel_policies ─────────────────────────────────────────

CREATE TABLE IF NOT EXISTS travel_policies (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id            UUID NOT NULL REFERENCES schemes(id),
    group_id             UUID NULL  REFERENCES groups(id),
    traveler_member_id   UUID NOT NULL REFERENCES members(id),

    policy_number        VARCHAR(50)  NOT NULL,
    trip_start_date      DATE         NOT NULL,
    trip_end_date        DATE         NOT NULL,
    destination_band     VARCHAR(20)  NULL
                         CHECK (destination_band IS NULL OR
                                destination_band IN ('DOMESTIC','EUROPE','ASIA','NORTH_AMERICA','OTHER')),
    coverage_level       VARCHAR(20)  NULL
                         CHECK (coverage_level IS NULL OR
                                coverage_level IN ('BASIC','STANDARD','COMPREHENSIVE')),
    pre_existing_declared BOOLEAN     NOT NULL DEFAULT FALSE,

    status               VARCHAR(20)  NOT NULL DEFAULT 'active'
                         CHECK (status IN ('active','suspended','terminated')),

    billing_override_amount NUMERIC(19,4) NULL CHECK (billing_override_amount IS NULL OR billing_override_amount > 0),
    billing_override_reason VARCHAR(40)   NULL,
    billing_override_effective_from DATE  NULL,
    CONSTRAINT travel_policies_override_requires_effective_from
        CHECK (billing_override_amount IS NULL OR billing_override_effective_from IS NOT NULL),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID NULL,
    updated_by UUID NULL,

    CONSTRAINT travel_policies_policy_number_unique UNIQUE (policy_number),
    CONSTRAINT travel_policies_trip_window CHECK (trip_end_date >= trip_start_date)
);
CREATE INDEX IF NOT EXISTS idx_travel_policies_scheme   ON travel_policies(scheme_id);
CREATE INDEX IF NOT EXISTS idx_travel_policies_group    ON travel_policies(group_id);
CREATE INDEX IF NOT EXISTS idx_travel_policies_traveler ON travel_policies(traveler_member_id);
CREATE INDEX IF NOT EXISTS idx_travel_policies_status   ON travel_policies(status);
CREATE INDEX IF NOT EXISTS idx_travel_policies_window   ON travel_policies(trip_start_date, trip_end_date);

-- ── DISABILITY / disability_policies ─────────────────────────────────

CREATE TABLE IF NOT EXISTS disability_policies (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id            UUID NOT NULL REFERENCES schemes(id),
    group_id             UUID NULL  REFERENCES groups(id),
    insured_member_id    UUID NOT NULL REFERENCES members(id),

    policy_number        VARCHAR(50)  NOT NULL,
    occupation_hazard_class VARCHAR(20) NULL
                         CHECK (occupation_hazard_class IS NULL OR
                                occupation_hazard_class IN ('SEDENTARY','MANUAL','HAZARDOUS','VERY_HAZARDOUS')),
    waiting_period_days  INTEGER      NOT NULL CHECK (waiting_period_days >= 0),
    benefit_period       VARCHAR(20)  NULL
                         CHECK (benefit_period IS NULL OR
                                benefit_period IN ('2_YEAR','5_YEAR','TO_AGE_65')),
    monthly_benefit      NUMERIC(19,4) NOT NULL CHECK (monthly_benefit >= 0),

    status               VARCHAR(20)  NOT NULL DEFAULT 'active'
                         CHECK (status IN ('active','suspended','terminated')),

    billing_override_amount NUMERIC(19,4) NULL CHECK (billing_override_amount IS NULL OR billing_override_amount > 0),
    billing_override_reason VARCHAR(40)   NULL,
    billing_override_effective_from DATE  NULL,
    CONSTRAINT disability_policies_override_requires_effective_from
        CHECK (billing_override_amount IS NULL OR billing_override_effective_from IS NOT NULL),

    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by UUID NULL,
    updated_by UUID NULL,

    CONSTRAINT disability_policies_policy_number_unique UNIQUE (policy_number)
);
CREATE INDEX IF NOT EXISTS idx_disability_policies_scheme ON disability_policies(scheme_id);
CREATE INDEX IF NOT EXISTS idx_disability_policies_group  ON disability_policies(group_id);
CREATE INDEX IF NOT EXISTS idx_disability_policies_member ON disability_policies(insured_member_id);
CREATE INDEX IF NOT EXISTS idx_disability_policies_status ON disability_policies(status);
