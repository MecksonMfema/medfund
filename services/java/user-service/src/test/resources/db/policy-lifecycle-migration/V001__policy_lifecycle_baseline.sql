-- ── Phase 13 §A IT baseline (per L18) ─────────────────────────────────
-- Minimal schema for the policy-lifecycle ITs (Phases 1-4). Mirrors the
-- production tenant-side tables the transition services touch:
--
--   • public.tenants + scheduled_job_configs shims (TenantAwareConnectionFactory
--     schema_name lookup + ScheduledJobRepository startup tick)
--   • Full R2DBC entity column sets for members / groups — memberRepository.save()
--     generates UPDATE statements listing every mapped @Column, so the IT
--     schema must carry the complete entity shape, not just the transition-
--     relevant slice (V001 baseline + V026 tightening + V042 trio + V043 reason).
--   • providers / schemes for FK targets + Phase 4 network_tier work
--   • V032 policy tables + V102 underwriting widening (bound_at,
--     coverage window, renewed_from_policy_id, widened status vocab)
--   • Phase 13 V111 policy_status_history + V112 member_status_history
--     + V113 providers.network_tier
--
-- Deliberately scoped to policy-status-transition testing only.
-- Force-widening the shared db/group-number-migration or
-- db/group-create-migration folders would cascade FKs into unrelated
-- ITs; this folder keeps the Phase 13 surface isolated.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE tenants (
    id          UUID        PRIMARY KEY,
    schema_name VARCHAR(63) NOT NULL DEFAULT 'public'
);

-- Empty shim so ScheduledJobRepository's startup tick doesn't 42P01
-- during context boot — same reason the group-* IT migrations carry it.
CREATE TABLE scheduled_job_configs (
    id                 UUID        PRIMARY KEY,
    tenant_id          UUID,
    job_type           VARCHAR(64),
    is_enabled         BOOLEAN     NOT NULL DEFAULT false,
    next_execution_at  TIMESTAMPTZ
);

CREATE TABLE groups (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name                            VARCHAR(200) NOT NULL,
    registration_number             VARCHAR(100),
    address                         TEXT,
    email                           VARCHAR(255),
    liaison_kind                    VARCHAR(30),
    liaison_user_id                 UUID,
    status                          VARCHAR(20)  NOT NULL DEFAULT 'active'
                                    CHECK (status IN ('active','suspended','terminated','deactivated')),
    scheduled_status                VARCHAR(20),
    scheduled_status_effective_from DATE,
    scheduled_status_reason         VARCHAR(500),
    suspend_reason                  VARCHAR(255),
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                      UUID,
    updated_by                      UUID,
    CONSTRAINT groups_registration_number_unique UNIQUE (registration_number)
);

CREATE TABLE schemes (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    insurance_line  VARCHAR(30)  NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    currency_code   CHAR(3)      NOT NULL DEFAULT 'USD',
    effective_date  DATE         NOT NULL DEFAULT CURRENT_DATE
);

-- Members carry the FULL entity shape (V001 + V026 NOT NULLs + V042 trio +
-- V043 suspend_reason + V030 override triple) so memberRepository.save()
-- round-trips without 42703s.
CREATE TABLE members (
    id                              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_number                   VARCHAR(50) NOT NULL UNIQUE,
    first_name                      VARCHAR(100) NOT NULL,
    last_name                       VARCHAR(100) NOT NULL,
    date_of_birth                   DATE NOT NULL,
    gender                          VARCHAR(20)  NOT NULL CHECK (gender IN ('male','female','other')),
    national_id                     VARCHAR(50)  NOT NULL,
    email                           VARCHAR(255) NOT NULL,
    phone                           VARCHAR(30),
    address                         TEXT,
    group_id                        UUID REFERENCES groups(id),
    scheme_id                       UUID NOT NULL REFERENCES schemes(id),
    keycloak_user_id                VARCHAR(255),
    status                          VARCHAR(20) NOT NULL DEFAULT 'active'
                                    CHECK (status IN ('enrolled','active','suspended','terminated','deactivated','lapsed')),
    enrollment_date                 DATE NOT NULL DEFAULT CURRENT_DATE,
    termination_date                DATE,
    scheduled_status                VARCHAR(20),
    scheduled_status_effective_from DATE,
    scheduled_status_reason         VARCHAR(500),
    suspend_reason                  VARCHAR(255),
    age_group_id                    UUID,
    billing_age_group_id            UUID,
    billing_override_reason         VARCHAR(500),
    billing_override_effective_from DATE,
    billing_override_amount         NUMERIC(19,4),
    created_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                      UUID,
    updated_by                      UUID,
    -- V026 mirrors
    CONSTRAINT members_enrollment_date_first_of_month CHECK (EXTRACT(DAY FROM enrollment_date) = 1)
);
CREATE UNIQUE INDEX uq_members_email_lower ON members (LOWER(email));
CREATE UNIQUE INDEX uq_members_national_id ON members (national_id);

CREATE TABLE providers (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    practice_number VARCHAR(50),
    specialty       VARCHAR(100),
    status          VARCHAR(20) NOT NULL DEFAULT 'active',
    network_tier    VARCHAR(20) NOT NULL DEFAULT 'STANDARD'
                    CHECK (network_tier IN ('STANDARD', 'TIER_1', 'TIER_2', 'TIER_3')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── Six annual-bind policy tables (V032 shape + V102 widening) ────────

CREATE TABLE life_policies (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id),
    group_id            UUID NULL REFERENCES groups(id),
    insured_member_id   UUID NOT NULL REFERENCES members(id),
    policy_number       VARCHAR(50) NOT NULL UNIQUE,
    sum_assured         NUMERIC(19,4) NOT NULL CHECK (sum_assured >= 0),
    occupation_hazard_class VARCHAR(30),
    term_months         INTEGER NOT NULL CHECK (term_months > 0),
    status              VARCHAR(20) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium')),
    billing_override_amount         NUMERIC(19,4),
    billing_override_reason         VARCHAR(500),
    billing_override_effective_from DATE,
    written_premium     NUMERIC(19,4),
    written_premium_currency CHAR(3),
    bound_at            TIMESTAMPTZ,
    coverage_start      DATE,
    coverage_end        DATE,
    renewed_from_policy_id UUID REFERENCES life_policies(id) ON DELETE SET NULL,
    portfolio_id        UUID,
    cohort_id           UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID
);

CREATE TABLE funeral_policies (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id),
    group_id            UUID NULL REFERENCES groups(id),
    principal_member_id UUID NOT NULL REFERENCES members(id),
    policy_number       VARCHAR(50) NOT NULL UNIQUE,
    cover_amount        NUMERIC(19,4) NOT NULL CHECK (cover_amount >= 0),
    lives_covered       INTEGER NOT NULL DEFAULT 1 CHECK (lives_covered >= 1),
    health_declaration  TEXT,
    status              VARCHAR(20) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium')),
    billing_override_amount         NUMERIC(19,4),
    billing_override_reason         VARCHAR(500),
    billing_override_effective_from DATE,
    written_premium     NUMERIC(19,4),
    written_premium_currency CHAR(3),
    bound_at            TIMESTAMPTZ,
    coverage_start      DATE,
    coverage_end        DATE,
    renewed_from_policy_id UUID REFERENCES funeral_policies(id) ON DELETE SET NULL,
    portfolio_id        UUID,
    cohort_id           UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID
);

CREATE TABLE disability_policies (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id),
    group_id            UUID NULL REFERENCES groups(id),
    insured_member_id   UUID NOT NULL REFERENCES members(id),
    policy_number       VARCHAR(50) NOT NULL UNIQUE,
    occupation_hazard_class VARCHAR(30),
    waiting_period_days INTEGER NOT NULL CHECK (waiting_period_days >= 0),
    benefit_period      VARCHAR(30),
    monthly_benefit     NUMERIC(19,4) NOT NULL CHECK (monthly_benefit >= 0),
    status              VARCHAR(20) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium')),
    billing_override_amount         NUMERIC(19,4),
    billing_override_reason         VARCHAR(500),
    billing_override_effective_from DATE,
    written_premium     NUMERIC(19,4),
    written_premium_currency CHAR(3),
    bound_at            TIMESTAMPTZ,
    coverage_start      DATE,
    coverage_end        DATE,
    renewed_from_policy_id UUID REFERENCES disability_policies(id) ON DELETE SET NULL,
    portfolio_id        UUID,
    cohort_id           UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID
);

CREATE TABLE travel_policies (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id),
    group_id            UUID NULL REFERENCES groups(id),
    traveler_member_id  UUID NOT NULL REFERENCES members(id),
    policy_number       VARCHAR(50) NOT NULL UNIQUE,
    trip_start_date     DATE NOT NULL,
    trip_end_date       DATE NOT NULL,
    destination_band    VARCHAR(30),
    coverage_level      VARCHAR(30),
    pre_existing_declared BOOLEAN,
    status              VARCHAR(20) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium')),
    billing_override_amount         NUMERIC(19,4),
    billing_override_reason         VARCHAR(500),
    billing_override_effective_from DATE,
    written_premium     NUMERIC(19,4),
    written_premium_currency CHAR(3),
    bound_at            TIMESTAMPTZ,
    renewed_from_policy_id UUID REFERENCES travel_policies(id) ON DELETE SET NULL,
    portfolio_id        UUID,
    cohort_id           UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID,
    CONSTRAINT travel_policies_trip_window CHECK (trip_end_date >= trip_start_date)
);

CREATE TABLE vehicles (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id),
    group_id            UUID NULL REFERENCES groups(id),
    owner_member_id     UUID NULL REFERENCES members(id),
    registration_number VARCHAR(32) NOT NULL UNIQUE,
    make                VARCHAR(60) NOT NULL,
    model               VARCHAR(60) NOT NULL,
    year                INTEGER NOT NULL CHECK (year BETWEEN 1900 AND 2100),
    vehicle_value       NUMERIC(19,4) NOT NULL CHECK (vehicle_value >= 0),
    body_type           VARCHAR(30),
    usage_type          VARCHAR(30),
    status              VARCHAR(20) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium')),
    billing_override_amount         NUMERIC(19,4),
    billing_override_reason         VARCHAR(500),
    billing_override_effective_from DATE,
    written_premium     NUMERIC(19,4),
    written_premium_currency CHAR(3),
    bound_at            TIMESTAMPTZ,
    coverage_start      DATE,
    coverage_end        DATE,
    renewed_from_policy_id UUID REFERENCES vehicles(id) ON DELETE SET NULL,
    portfolio_id        UUID,
    cohort_id           UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID
);

CREATE TABLE properties (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id),
    group_id            UUID NULL REFERENCES groups(id),
    owner_member_id     UUID NULL REFERENCES members(id),
    property_name       VARCHAR(200) NOT NULL,
    address             TEXT NOT NULL,
    sum_insured         NUMERIC(19,4) NOT NULL CHECK (sum_insured >= 0),
    construction_type   VARCHAR(30),
    roof_type           VARCHAR(30),
    location_risk_band  VARCHAR(30),
    security_features_count INTEGER,
    property_age_years  INTEGER,
    status              VARCHAR(20) NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active','lapsed','suspended','terminated','draft','legacy_no_premium')),
    billing_override_amount         NUMERIC(19,4),
    billing_override_reason         VARCHAR(500),
    billing_override_effective_from DATE,
    written_premium     NUMERIC(19,4),
    written_premium_currency CHAR(3),
    bound_at            TIMESTAMPTZ,
    coverage_start      DATE,
    coverage_end        DATE,
    renewed_from_policy_id UUID REFERENCES properties(id) ON DELETE SET NULL,
    portfolio_id        UUID,
    cohort_id           UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID,
    updated_by          UUID
);

-- ── Phase 13 history tables (production V111 + V112 shape) ───────────

CREATE TABLE policy_status_history (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id        UUID NOT NULL,
    policy_source    VARCHAR(30) NOT NULL,
    from_status      VARCHAR(30),
    to_status        VARCHAR(30) NOT NULL,
    effective_at     TIMESTAMPTZ NOT NULL,
    actor_id         UUID,
    actor_email      VARCHAR(255),
    reason_code      VARCHAR(50),
    reason_note      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_policy_status_history_source CHECK (policy_source IN (
        'LIFE_POLICY', 'FUNERAL_POLICY', 'DISABILITY_POLICY',
        'TRAVEL_POLICY', 'VEHICLE_POLICY', 'PROPERTY_POLICY'
    ))
);
CREATE INDEX ix_policy_status_history_policy_effective
    ON policy_status_history (policy_id, effective_at DESC);
CREATE INDEX ix_policy_status_history_source_status_effective
    ON policy_status_history (policy_source, to_status, effective_at DESC);

CREATE TABLE member_status_history (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id        UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    from_status      VARCHAR(30),
    to_status        VARCHAR(30) NOT NULL,
    effective_at     TIMESTAMPTZ NOT NULL,
    actor_id         UUID,
    actor_email      VARCHAR(255),
    reason_code      VARCHAR(50),
    reason_note      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_member_status_history_reason CHECK (reason_code IS NULL OR reason_code IN (
        'initial_backfill', 'backfill_from_termination_date', 'backfill_from_suspend_reason',
        'admin_activate', 'admin_suspend', 'admin_terminate', 'admin_deactivate', 'admin_lapse',
        'arrears_lapse', 'arrears_clear', 'scheduled_change', 'group_cascade',
        'dependant_swap', 'enrolment', 'auto_termination', 'other'
    ))
);
CREATE INDEX ix_member_status_history_member_effective
    ON member_status_history (member_id, effective_at DESC);
CREATE INDEX ix_member_status_history_status_effective
    ON member_status_history (to_status, effective_at DESC);
