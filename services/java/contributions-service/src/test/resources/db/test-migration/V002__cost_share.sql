-- Test-side mirror of the tenant V076 migration. The contributions-service
-- doesn't own the production tenant migrations (those live under
-- tenancy-service/…/migration/tenant), so ITs need a matching schema in the
-- test's `public` search_path to exercise the repositories end-to-end.
-- Keep in sync with V076__cost_share_config.sql.

CREATE TABLE scheme_cost_share (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id           UUID NOT NULL REFERENCES schemes(id) ON DELETE CASCADE,
    policy_year         INTEGER NOT NULL,
    deductible          DECIMAL(19,4),
    out_of_pocket_max   DECIMAL(19,4),
    deductible_scope    VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL',
    oop_scope           VARCHAR(20) NOT NULL DEFAULT 'INDIVIDUAL',
    shortfall_policy    VARCHAR(30) NOT NULL DEFAULT 'RECOVER_FROM_MEMBER',
    currency_code       CHAR(3) NOT NULL,
    effective_from      DATE NOT NULL,
    effective_to        DATE,
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

CREATE TABLE benefit_cost_share (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_benefit_id        UUID NOT NULL REFERENCES scheme_benefits(id) ON DELETE CASCADE,
    copay_type               VARCHAR(20),
    copay_amount             DECIMAL(19,4),
    copay_percentage         DECIMAL(7,4),
    copay_max                DECIMAL(19,4),
    coinsurance_rate         DECIMAL(7,4),
    applies_to_deductible    BOOLEAN NOT NULL DEFAULT TRUE,
    applies_to_oop_max       BOOLEAN NOT NULL DEFAULT TRUE,
    basis                    VARCHAR(20) NOT NULL DEFAULT 'per_visit',
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

CREATE TABLE benefit_cost_share_tier (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    benefit_cost_share_id    UUID NOT NULL REFERENCES benefit_cost_share(id) ON DELETE CASCADE,
    tier_name                VARCHAR(100) NOT NULL,
    copay_amount             DECIMAL(19,4),
    copay_percentage         DECIMAL(7,4),
    copay_max                DECIMAL(19,4),
    UNIQUE (benefit_cost_share_id, tier_name)
);

CREATE TABLE member_cost_share_accumulator (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id           UUID NOT NULL,
    dependant_id        UUID,
    scheme_id           UUID NOT NULL,
    policy_year         INTEGER NOT NULL,
    deductible_met      DECIMAL(19,4) NOT NULL DEFAULT 0,
    oop_met             DECIMAL(19,4) NOT NULL DEFAULT 0,
    copay_count         INTEGER NOT NULL DEFAULT 0,
    currency_code       CHAR(3) NOT NULL,
    version             INTEGER NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX ux_member_cost_share_accumulator
    ON member_cost_share_accumulator (
        member_id,
        COALESCE(dependant_id, '00000000-0000-0000-0000-000000000000'::uuid),
        scheme_id,
        policy_year
    );
