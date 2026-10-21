-- Test-migration layer for the Phase 18 (K8) PremiumAggregateIT.
-- Minimal earning_schedule + the seven policy-source tables the seven-way
-- policy_enrichment CTE joins into for SCHEME dimension queries. Only the
-- columns the aggregate query actually reads are materialised.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- earning_schedule mirrors production V109 shape (Phase 12 §A) — the
-- aggregate query only reads currency_code, insurance_line, period_end,
-- earned_at_period_end, policy_id, policy_source.
CREATE TABLE earning_schedule (
    id                    UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    policy_id             UUID           NOT NULL,
    policy_source         VARCHAR(30)    NOT NULL,
    insurance_line        VARCHAR(20)    NOT NULL,
    period_start          DATE           NOT NULL,
    period_end            DATE           NOT NULL,
    written_amount        NUMERIC(19, 4) NOT NULL,
    earned_at_period_end  NUMERIC(19, 4),
    currency_code         CHAR(3)        NOT NULL,
    is_endorsement        BOOLEAN        NOT NULL DEFAULT FALSE,
    endorsement_id        UUID,
    created_at            TIMESTAMPTZ    NOT NULL DEFAULT NOW()
);

-- Seven policy-source tables — only the columns the policy_enrichment
-- CTE selects (id + scheme_id). Zero foreign keys; the IT seeds directly.
CREATE TABLE life_policies (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id UUID
);

CREATE TABLE funeral_policies (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id UUID
);

CREATE TABLE disability_policies (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id UUID
);

CREATE TABLE travel_policies (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id UUID
);

CREATE TABLE vehicles (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id UUID
);

CREATE TABLE properties (
    id        UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id UUID
);

-- Contributions: seven-way CTE selects id + scheme_id from this table too.
CREATE TABLE contributions (
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id  UUID,
    scheme_id  UUID,
    amount     NUMERIC(19, 4),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
