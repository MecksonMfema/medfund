-- Minimal test schema for SchemeService integration tests. Mirrors the
-- columns the production tenant-side V001__baseline.sql creates for `schemes`,
-- `scheme_benefits`, and `age_groups` — the exact subset SchemeService.create /
-- createBenefit / createAgeGroup write. Lives in `public` so the test does not
-- have to spin a tenant schema; tenant isolation is asserted via the audit
-- envelope (which carries the tenant_id) rather than schema selection.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

CREATE TABLE schemes (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                   VARCHAR(200) NOT NULL UNIQUE,
    description            TEXT,
    scheme_type            VARCHAR(50)  NOT NULL DEFAULT 'medical_aid',
    insurance_line         VARCHAR(32)  NOT NULL DEFAULT 'HEALTH',
    status                 VARCHAR(20)  NOT NULL DEFAULT 'active',
    effective_date         DATE,
    end_date               DATE,
    currency_code          VARCHAR(3),
    -- V050 age gates + V061 product-level ledger opt-out. Kept inline
    -- so the IT's baseline mirrors the production schema shape.
    min_age                SMALLINT,
    max_age                SMALLINT,
    tracks_member_balances BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ  NOT NULL DEFAULT now(),
    created_by             UUID,
    updated_by             UUID
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
    status               VARCHAR(20)  NOT NULL DEFAULT 'active',
    -- V051 age gate + payout-mode flag; V061 usage classification.
    min_age              SMALLINT,
    max_age              SMALLINT,
    cash_claim_allowed   BOOLEAN      NOT NULL DEFAULT TRUE,
    usage_mode           VARCHAR(32)  NOT NULL DEFAULT 'RUNNING_BALANCE',
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

-- ── Shims for shared/ infra that queries platform-wide tables on
-- connection-acquisition or on a scheduled tick. This IT is a
-- schemes-slice test and doesn't seed either table; both are here
-- purely so the reactive connection factory + scheduler find a valid
-- relation and fall through their empty paths instead of erroring
-- with 42P01.

-- Read by TenantAwareConnectionFactory.lookupSchemaName on every
-- tenant-scoped connection acquisition. An empty result triggers the
-- factory's documented fall-back to schema=public, which is what this
-- test wants — SchemeServiceIT writes to public tables.
CREATE TABLE tenants (
    id           UUID         PRIMARY KEY,
    schema_name  VARCHAR(63)  NOT NULL DEFAULT 'public'
);

-- Read by ScheduledJobRepository on the dispatcher's fixed-delay tick.
-- Empty = no jobs enabled = the scheduler no-ops. Columns match the
-- @Query WHERE-clause shape (is_enabled + next_execution_at) so the
-- reactive R2DBC layer can bind and execute.
CREATE TABLE scheduled_job_configs (
    id                 UUID         PRIMARY KEY,
    tenant_id          UUID,
    job_type           VARCHAR(64),
    is_enabled         BOOLEAN      NOT NULL DEFAULT false,
    next_execution_at  TIMESTAMPTZ
);
