-- =====================================================================
-- V076: Cost-share configuration + member accumulators.
--
-- Phase 1 of the copayments standard flow. Creates:
--   1. scheme_cost_share            — per-scheme deductible/OOP-max/shortfall
--                                     policy (temporal, keyed by policy_year)
--   2. benefit_cost_share           — per-benefit copay/coinsurance (temporal,
--                                     1:1-nullable with scheme_benefits)
--   3. benefit_cost_share_tier      — 1:N tiered copays when copay_type=TIERED
--   4. member_cost_share_accumulator — family-aware deductible_met + oop_met
--                                     ledger, one row per
--                                     (member, dependant, scheme, policy_year)
--
-- All four tables live in the tenant schema (never prefix public.<name>);
-- see bug_public_prefix_silent_rollback. Every mutation is temporal via
-- effective_from / effective_to — config edits create new rows rather than
-- mutating the current one, so a claim adjudicated last week reads the
-- config that was effective then.
-- =====================================================================

-- 1. Scheme-level cost-share (temporal per G15).
CREATE TABLE scheme_cost_share (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id) ON DELETE CASCADE,
    policy_year         INTEGER NOT NULL,                       -- aligns with beneficiary_benefits.policy_year (G17)
    deductible          DECIMAL(19,4),
    out_of_pocket_max   DECIMAL(19,4),
    deductible_scope    VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL',
    oop_scope           VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL',
    shortfall_policy    VARCHAR(30) NOT NULL DEFAULT 'RECOVER_FROM_MEMBER',
    currency_code       CHAR(3) NOT NULL,
    effective_from      DATE NOT NULL,
    effective_to        DATE,                                    -- NULL = currently effective
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by          UUID NOT NULL,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by          UUID,
    CONSTRAINT scheme_cost_share_scope_ck
        CHECK (deductible_scope IN ('INDIVIDUAL','FAMILY','EMBEDDED')
           AND oop_scope         IN ('INDIVIDUAL','FAMILY','EMBEDDED')
           AND shortfall_policy  IN ('RECOVER_FROM_MEMBER','ABSORB_BY_FUND'))
);
CREATE INDEX ix_scheme_cost_share_lookup
    ON scheme_cost_share (scheme_id, policy_year, effective_from);

-- 2. Benefit-level cost-share (temporal, per-benefit 1:1-nullable).
CREATE TABLE benefit_cost_share (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_benefit_id        UUID NOT NULL REFERENCES scheme_benefits(id) ON DELETE CASCADE,
    copay_type               VARCHAR(20),                       -- FLAT | PERCENT | TIERED | NULL (coinsurance-only or no cost share)
    copay_amount             DECIMAL(19,4),
    copay_percentage         DECIMAL(7,4),                      -- 0.0000-100.0000
    copay_max                DECIMAL(19,4),
    coinsurance_rate         DECIMAL(7,4),
    applies_to_deductible    BOOLEAN NOT NULL DEFAULT TRUE,
    applies_to_oop_max       BOOLEAN NOT NULL DEFAULT TRUE,
    basis                    VARCHAR(20) NOT NULL DEFAULT 'per_visit',  -- per_visit | per_day | per_admission | per_script
    effective_from           DATE NOT NULL,
    effective_to             DATE,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by               UUID NOT NULL,
    updated_at               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by               UUID,
    CONSTRAINT benefit_cost_share_copay_type_ck
        CHECK (copay_type IS NULL OR copay_type IN ('FLAT','PERCENT','TIERED'))
);
CREATE INDEX ix_benefit_cost_share_lookup
    ON benefit_cost_share (scheme_benefit_id, effective_from);

-- 3. Tiered copay rows (1:N with benefit_cost_share; free-text tier_name for
--    MVP per G16 — a network_tiers reference table is deferred as F5).
CREATE TABLE benefit_cost_share_tier (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    benefit_cost_share_id    UUID NOT NULL REFERENCES benefit_cost_share(id) ON DELETE CASCADE,
    tier_name                VARCHAR(100) NOT NULL,             -- e.g. "TIER_1", "IN_NETWORK", "PREFERRED"
    copay_amount             DECIMAL(19,4),
    copay_percentage         DECIMAL(7,4),
    copay_max                DECIMAL(19,4),
    UNIQUE (benefit_cost_share_id, tier_name)
);

-- 4. Member accumulators — family-aware per G8.
--    dependant_id IS NULL is used two ways:
--      - FAMILY scope: the family pot (only one row per member/scheme/year).
--      - INDIVIDUAL scope on the principal member: the member's own row.
--    Under FAMILY scope, per-dependant claims still increment the shared row.
--    Under INDIVIDUAL scope, each dependant has its own row keyed by dependant_id.
--    Under EMBEDDED, both the per-dependant row and the family pot row are updated.
CREATE TABLE member_cost_share_accumulator (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id           UUID NOT NULL,
    dependant_id        UUID,                                    -- NULL = principal / family pot
    scheme_id           UUID NOT NULL,
    policy_year         INTEGER NOT NULL,                        -- G17
    deductible_met      DECIMAL(19,4) NOT NULL DEFAULT 0,
    oop_met             DECIMAL(19,4) NOT NULL DEFAULT 0,
    copay_count         INTEGER NOT NULL DEFAULT 0,
    currency_code       CHAR(3) NOT NULL,
    version             INTEGER NOT NULL DEFAULT 0,              -- optimistic lock for concurrent claim writes
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- COALESCE trick borrowed from beneficiary_benefits — lets us keep NULL
-- dependant_id semantics AND enforce uniqueness in a single index.
CREATE UNIQUE INDEX ux_member_cost_share_accumulator
    ON member_cost_share_accumulator (
        member_id,
        COALESCE(dependant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        scheme_id,
        policy_year
    );
