-- Minimal schema for the BillingService chargePreview IT. Includes only
-- the columns the HealthCandidateResolver + chargePreview pipeline reads
-- so the fixture stays maintainable. Lives in `public` — the resolver
-- and preview pipeline aren't tenant-schema-routed at the SQL level
-- (schema routing is one layer up), so a single-schema fixture is
-- sufficient.

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ── Platform shims for shared/ infra ─────────────────────────────────
-- TenantAwareConnectionFactory.lookupSchemaName + BillingService.
-- getPricingModel both query public.tenants. pricing_model is what
-- gates the isCustomPriced flag (INDIVIDUAL model → override wins;
-- STANDARD → override is ignored).
CREATE TABLE tenants (
    id            UUID          PRIMARY KEY,
    schema_name   VARCHAR(63)   NOT NULL DEFAULT 'public',
    pricing_model VARCHAR(20)   NOT NULL DEFAULT 'STANDARD'
);

-- Read by ScheduledJobRepository on the dispatcher's tick. Empty →
-- scheduler no-ops. Same shim shape as SchemeServiceIT's migration.
CREATE TABLE scheduled_job_configs (
    id                 UUID         PRIMARY KEY,
    tenant_id          UUID,
    job_type           VARCHAR(64),
    is_enabled         BOOLEAN      NOT NULL DEFAULT false,
    next_execution_at  TIMESTAMPTZ
);

-- TenantRuleLoader.ensureLoaded reads this on every priced contribution
-- via ContributionPricingService.price. Empty → the pricing service
-- returns the contribution's amount untouched (the resolver's value
-- wins, which is what the chargePreview tests actually want to
-- observe). Columns match the loader's SELECT (id/tenant_id/enabled/
-- priority/created_at/…); we only need those four for the loader
-- to parse a zero-row result.
CREATE TABLE tenant_rules (
    id           UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID          NOT NULL,
    enabled      BOOLEAN       NOT NULL DEFAULT true,
    priority     INTEGER       NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    rule_type    VARCHAR(64),
    name         VARCHAR(200),
    description  TEXT,
    definition   TEXT
);

-- Platform-wide staff table — the resolver's group-block LEFT JOINs it
-- for the STAFF-kind liaison branch. Not exercised by the charge-
-- preview IT but must exist so the JOIN parses.
CREATE TABLE staff_users (
    id     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    email  VARCHAR(200)
);

-- ── Contribution-domain tables ───────────────────────────────────────

CREATE TABLE schemes (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(200) NOT NULL,
    scheme_type     VARCHAR(50)  NOT NULL DEFAULT 'medical_aid',
    insurance_line  VARCHAR(32)  NOT NULL DEFAULT 'HEALTH',
    status          VARCHAR(20)  NOT NULL DEFAULT 'active',
    currency_code   VARCHAR(3),
    effective_date  DATE,
    end_date        DATE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE age_groups (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id             UUID          REFERENCES schemes(id) ON DELETE CASCADE,
    name                  VARCHAR(100)  NOT NULL,
    min_age               INTEGER       NOT NULL DEFAULT 0,
    max_age               INTEGER       NOT NULL DEFAULT 200,
    contribution_amount   NUMERIC(18,2) NOT NULL DEFAULT 0,
    currency_code         VARCHAR(3),
    status                VARCHAR(20)   NOT NULL DEFAULT 'active',
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- The resolver's LATERAL join reads from this. One row per
-- (age_group_id, effective_from) window; the LATERAL picks the row
-- with the greatest effective_from <= periodEnd.
CREATE TABLE age_group_prices (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    age_group_id          UUID          NOT NULL REFERENCES age_groups(id) ON DELETE CASCADE,
    contribution_amount   NUMERIC(18,2) NOT NULL,
    currency_code         VARCHAR(3),
    effective_from        DATE          NOT NULL,
    effective_to          DATE
);

CREATE TABLE groups (
    id                    UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name                  VARCHAR(200)  NOT NULL,
    registration_number   VARCHAR(50),
    email                 VARCHAR(200),
    status                VARCHAR(20)   NOT NULL DEFAULT 'active',
    liaison_kind          VARCHAR(20),
    liaison_user_id       UUID
);

CREATE TABLE group_liaisons (
    id     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    email  VARCHAR(200)
);

-- The most-loaded fixture table. billing_* columns drive the custom-
-- pricing + scheduled-scheme-change flags; termination_date drives the
-- chargePreview terminating filter.
CREATE TABLE members (
    id                              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    first_name                      VARCHAR(120),
    last_name                       VARCHAR(120),
    email                           VARCHAR(200),
    member_number                   VARCHAR(50),
    group_id                        UUID          REFERENCES groups(id),
    scheme_id                       UUID          REFERENCES schemes(id),
    status                          VARCHAR(20)   NOT NULL DEFAULT 'active',
    enrollment_date                 DATE          NOT NULL DEFAULT (CURRENT_DATE - INTERVAL '365 days'),
    termination_date                DATE,
    age_group_id                    UUID          REFERENCES age_groups(id),
    billing_age_group_id            UUID          REFERENCES age_groups(id),
    billing_override_amount         NUMERIC(18,2),
    billing_override_effective_from DATE
);

CREATE TABLE dependants (
    id                              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id                       UUID          NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    first_name                      VARCHAR(120),
    last_name                       VARCHAR(120),
    status                          VARCHAR(20)   NOT NULL DEFAULT 'active',
    age_group_id                    UUID          REFERENCES age_groups(id),
    billing_age_group_id            UUID          REFERENCES age_groups(id),
    billing_override_amount         NUMERIC(18,2),
    billing_override_effective_from DATE,
    created_at                      TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- ── Tables BillingService touches transitively ────────────────────────
-- chargePreview itself doesn't write to these, but BillingService's
-- other public methods do, and the Spring auto-config wires their
-- repositories at startup. Present as minimal stubs so the beans wire
-- without a 42P01 at bean-construction time.

CREATE TABLE contributions (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id       UUID,
    dependant_id    UUID,
    scheme_id       UUID,
    group_id        UUID,
    age_group_id    UUID,
    amount          NUMERIC(18,2),
    currency_code   VARCHAR(3),
    period_start    DATE,
    period_end      DATE,
    status          VARCHAR(20),
    payment_method  VARCHAR(50),
    payment_reference VARCHAR(100),
    paid_at         TIMESTAMPTZ,
    insurance_line  VARCHAR(32),
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    created_by      UUID,
    updated_by      UUID
);

CREATE TABLE invoices (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_number  VARCHAR(50),
    group_id        UUID,
    member_id       UUID,
    scheme_id       UUID,
    period_start    DATE,
    period_end      DATE,
    due_date        DATE,
    total_amount    NUMERIC(18,2),
    currency_code   VARCHAR(3),
    status          VARCHAR(20),
    created_at      TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ,
    committed_at    TIMESTAMPTZ,
    created_by      UUID
);

CREATE TABLE billing_cycle_config (
    id                      UUID          PRIMARY KEY,
    commit_cooldown_hours   INTEGER       NOT NULL DEFAULT 24,
    last_committed_at       TIMESTAMPTZ
);

CREATE TABLE member_running_balance (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id        UUID         NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    currency_code    VARCHAR(3)   NOT NULL,
    balance          NUMERIC(18,2) NOT NULL DEFAULT 0,
    last_charge_at   TIMESTAMPTZ,
    last_payment_at  TIMESTAMPTZ,
    UNIQUE (member_id, currency_code)
);

CREATE TABLE group_running_balance (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id         UUID         NOT NULL REFERENCES groups(id) ON DELETE CASCADE,
    currency_code    VARCHAR(3)   NOT NULL,
    balance          NUMERIC(18,2) NOT NULL DEFAULT 0,
    last_charge_at   TIMESTAMPTZ,
    last_payment_at  TIMESTAMPTZ,
    UNIQUE (group_id, currency_code)
);

CREATE TABLE dunning_config (
    id                  UUID         PRIMARY KEY,
    suspension_days     INTEGER      NOT NULL DEFAULT 30,
    deactivation_days   INTEGER      NOT NULL DEFAULT 90,
    grace_days          INTEGER      NOT NULL DEFAULT 7
);

-- invoice_pdfs pointer table — BillingService.findPdfPointer joins to it.
CREATE TABLE invoice_pdfs (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_id     UUID          NOT NULL REFERENCES invoices(id) ON DELETE CASCADE,
    bucket         VARCHAR(100)  NOT NULL,
    object_key     VARCHAR(500)  NOT NULL,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
