-- Minimal test schema for SchemeService integration tests. Mirrors the
-- columns the production tenant-side V001__baseline.sql creates for `schemes`,
-- `scheme_benefits`, and `age_groups` — the exact subset SchemeService.create /
-- createBenefit / createAgeGroup write. Lives in `public` so the test does not
-- have to spin a tenant schema; tenant isolation is asserted via the audit
-- envelope (which carries the tenant_id) rather than schema selection.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE schemes (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL UNIQUE,
    description     TEXT,
    scheme_type     VARCHAR(50)  NOT NULL DEFAULT 'medical_aid',
    insurance_line  VARCHAR(32)  NOT NULL DEFAULT 'HEALTH',
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    effective_date  DATE,
    end_date        DATE,
    currency_code   VARCHAR(3),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by      UUID,
    updated_by      UUID
);

CREATE TABLE scheme_benefits (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id            UUID         NOT NULL REFERENCES schemes(id) ON DELETE CASCADE,
    name                 VARCHAR(200) NOT NULL,
    benefit_type         VARCHAR(50)  NOT NULL,
    annual_limit         NUMERIC(18,2),
    daily_limit          NUMERIC(18,2),
    event_limit          NUMERIC(18,2),
    currency_code        VARCHAR(3),
    waiting_period_days  INTEGER,
    description          TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE age_groups (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id             UUID         NOT NULL REFERENCES schemes(id) ON DELETE CASCADE,
    name                  VARCHAR(100) NOT NULL,
    min_age               INTEGER      NOT NULL,
    max_age               INTEGER      NOT NULL,
    contribution_amount   NUMERIC(18,2) NOT NULL,
    currency_code         VARCHAR(3),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
