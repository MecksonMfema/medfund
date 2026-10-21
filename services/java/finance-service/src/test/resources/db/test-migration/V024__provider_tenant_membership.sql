-- Mirror of public.provider_tenants + public.provider_insurance_lines
-- (tenancy-service public/V185) for the finance-service ITs, plus the
-- driving tables the five *ProviderJoinIT classes read through.
--
-- Every provider join in the finance query repositories is now
-- membership-guarded against provider_tenants, so a fixture has to be able
-- to put the membership row that makes a seeded provider visible to the IT
-- tenant. The IT schema IS `public` (V001 seeds a `tenants` row whose
-- schema_name is 'public'), so the production SQL's
-- `public.provider_tenants` hits these tables directly.
--
-- Shapes are trimmed to the columns the finance repositories actually
-- select: the contract metadata V185 carries (credit limit, tariff
-- agreement, effective dates) has no reader in this service.

CREATE TABLE IF NOT EXISTS provider_tenants (
    provider_id   UUID        NOT NULL,
    tenant_id     UUID        NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'active',
    network_tier  VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    in_network    BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_id, tenant_id)
);

CREATE TABLE IF NOT EXISTS provider_insurance_lines (
    provider_id     UUID        NOT NULL,
    insurance_line  VARCHAR(20) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (provider_id, insurance_line)
);

-- No FK onto providers: the ITs DELETE FROM providers between tests and an
-- inbound FK would force a CASCADE they do not ask for.

-- ── Columns the creditors branch projects off public.providers ───────
-- V005 created a name-only providers mirror; V106 renamed the platform
-- table's practice_number to registration_number, which the creditors
-- query now projects as subject_code.
ALTER TABLE providers ADD COLUMN IF NOT EXISTS registration_number VARCHAR(100);
ALTER TABLE providers ADD COLUMN IF NOT EXISTS email               VARCHAR(255);

-- The creditors union projects subject_email from both halves, so the member
-- mirror needs the column too (V001 created members without it, because
-- nothing had exercised the member branch of that query before).
ALTER TABLE members   ADD COLUMN IF NOT EXISTS email               VARCHAR(255);

-- ── Driving tables for the provider-join ITs ─────────────────────────
CREATE TABLE IF NOT EXISTS provider_balances (
    id                   UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    provider_id          UUID           NOT NULL,
    total_claimed        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_approved       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    total_paid           NUMERIC(19, 4) NOT NULL DEFAULT 0,
    outstanding_balance  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    currency_code        VARCHAR(3)     NOT NULL,
    last_updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_provider_balances_it UNIQUE (provider_id, currency_code)
);

CREATE TABLE IF NOT EXISTS notes (
    id                UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    note_number       VARCHAR(50)    NOT NULL UNIQUE,
    provider_id       UUID,
    member_id         UUID,
    direction         VARCHAR(6)     NOT NULL,
    note_type         VARCHAR(40),
    type              VARCHAR(10)    NOT NULL DEFAULT 'ORIGINAL',
    reverses_note_id  UUID,
    amount            NUMERIC(19, 4) NOT NULL,
    currency_code     VARCHAR(3)     NOT NULL,
    reason            TEXT,
    status            VARCHAR(32)    NOT NULL DEFAULT 'pending',
    approved_by       UUID,
    approved_at       TIMESTAMPTZ,
    posted_at         TIMESTAMPTZ,
    created_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),
    created_by        UUID
);

CREATE TABLE IF NOT EXISTS advance_payments (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id      UUID,
    provider_id     UUID,
    member_id       UUID,
    amount          NUMERIC(19, 4) NOT NULL,
    currency_code   VARCHAR(3)     NOT NULL,
    payment_method  VARCHAR(50),
    reference       VARCHAR(255),
    comment         TEXT,
    recorded_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    type            VARCHAR(20)    NOT NULL DEFAULT 'ADVANCE',
    status          VARCHAR(20)    NOT NULL DEFAULT 'open'
);

CREATE TABLE IF NOT EXISTS payment_advices (
    id                      UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    advice_number           VARCHAR(50)    NOT NULL UNIQUE,
    payment_run_id          UUID,
    payee_type              VARCHAR(10)    NOT NULL DEFAULT 'PROVIDER',
    provider_id             UUID,
    member_id               UUID,
    currency_code           VARCHAR(3)     NOT NULL,
    total_amount            NUMERIC(19, 4) NOT NULL DEFAULT 0,
    claim_count             INTEGER        NOT NULL DEFAULT 0,
    status                  VARCHAR(32)    NOT NULL DEFAULT 'generated',
    issued_at               TIMESTAMPTZ    NOT NULL DEFAULT now(),
    period_start_at         TIMESTAMPTZ,
    period_end_at           TIMESTAMPTZ,
    carried_in_amount       NUMERIC(19, 4) NOT NULL DEFAULT 0,
    claims_paid_amount      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    ctc_applied_amount      NUMERIC(19, 4) NOT NULL DEFAULT 0,
    advance_applied_amount  NUMERIC(19, 4) NOT NULL DEFAULT 0,
    tax_withheld_amount     NUMERIC(19, 4) NOT NULL DEFAULT 0,
    shortfall_amount        NUMERIC(19, 4) NOT NULL DEFAULT 0,
    net_due_amount          NUMERIC(19, 4) NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now()
);

-- The tenant role established in V001 was granted CRUD on the tables that
-- existed then; tables created afterwards need their own grant so the
-- tenant connection (SET ROLE public_role) can read and seed them.
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
