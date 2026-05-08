-- Tenant-configurable billing catalogues. Replaces legacy MASCA's hard-coded
-- enums (15 benefit types, ADULT/CHILD/SENIOR age groups, CASH/BANK_TRANSFER
-- payment methods, ORDINARY/ADJUSTMENT/CTC/CREDIT/DEBIT transaction types,
-- 30 / 90-day bad-debt thresholds, 3-hour commit cooldown, monthly cycle).
-- Each tenant gets their own seeded catalogue and can rename, disable, or
-- extend any row from the Tenant Admin → Settings → Billing tab.

CREATE TABLE benefit_types (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code            VARCHAR(50)  NOT NULL UNIQUE,
    label           VARCHAR(200) NOT NULL,
    description     TEXT,
    sort_order      INTEGER      NOT NULL DEFAULT 0,
    is_active       BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_benefit_types_active ON benefit_types(is_active) WHERE is_active = TRUE;

CREATE TABLE payment_methods (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code               VARCHAR(50)  NOT NULL UNIQUE,
    label              VARCHAR(200) NOT NULL,
    description        TEXT,
    requires_reference BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_methods_active ON payment_methods(is_active) WHERE is_active = TRUE;

CREATE TABLE transaction_types (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code               VARCHAR(50)  NOT NULL UNIQUE,
    label              VARCHAR(200) NOT NULL,
    description        TEXT,
    sign               CHAR(1)      NOT NULL CHECK (sign IN ('+','-')),
    requires_approval  BOOLEAN      NOT NULL DEFAULT FALSE,
    is_active          BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transaction_types_active ON transaction_types(is_active) WHERE is_active = TRUE;

-- Single-row config tables. The id is fixed so the upsert is "always upsert
-- the same row". A trigger or service-layer guard would be redundant — the
-- application addresses these by their well-known UUID.

CREATE TABLE dunning_config (
    id                  UUID         PRIMARY KEY,
    grace_days          INTEGER      NOT NULL DEFAULT 7  CHECK (grace_days >= 0),
    suspension_days     INTEGER      NOT NULL DEFAULT 30 CHECK (suspension_days >= 0),
    write_off_days      INTEGER      NOT NULL DEFAULT 90 CHECK (write_off_days >= 0),
    auto_suspend        BOOLEAN      NOT NULL DEFAULT FALSE,
    auto_write_off      BOOLEAN      NOT NULL DEFAULT FALSE,
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by          UUID
);

CREATE TABLE billing_cycle_config (
    id                    UUID         PRIMARY KEY,
    frequency             VARCHAR(20)  NOT NULL DEFAULT 'MONTHLY'
                          CHECK (frequency IN ('MONTHLY','QUARTERLY','ANNUAL','CUSTOM')),
    day_of_month          SMALLINT     NOT NULL DEFAULT 1
                          CHECK (day_of_month BETWEEN 1 AND 28),
    auto_generate         BOOLEAN      NOT NULL DEFAULT FALSE,
    commit_cooldown_hours SMALLINT     NOT NULL DEFAULT 3
                          CHECK (commit_cooldown_hours >= 0),
    last_committed_at     TIMESTAMPTZ,
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by            UUID
);

-- ── Operational tables that the entities reference but V001 never created ──
-- Both Transaction and Invoice entities exist in contributions-service but
-- their DDL was never written. Adding them here so the catalogue FKs below
-- have something to attach to and the operational billing pages can run.

CREATE TABLE IF NOT EXISTS invoices (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    invoice_number  VARCHAR(50)   NOT NULL UNIQUE,
    group_id        UUID          REFERENCES groups(id),
    member_id       UUID          REFERENCES members(id),
    scheme_id       UUID          REFERENCES schemes(id),
    total_amount    DECIMAL(19,4) NOT NULL,
    currency_code   CHAR(3)       NOT NULL DEFAULT 'USD',
    status          VARCHAR(20)   NOT NULL DEFAULT 'draft',
    period_start    DATE,
    period_end      DATE,
    due_date        DATE,
    issued_at       TIMESTAMPTZ,
    paid_at         TIMESTAMPTZ,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by      UUID
);

CREATE INDEX IF NOT EXISTS idx_invoices_group  ON invoices(group_id)  WHERE group_id  IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_invoices_member ON invoices(member_id) WHERE member_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_invoices_status ON invoices(status);

CREATE TABLE IF NOT EXISTS transactions (
    id                  UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    transaction_number  VARCHAR(50)   NOT NULL UNIQUE,
    contribution_id     UUID          REFERENCES contributions(id),
    invoice_id          UUID          REFERENCES invoices(id),
    amount              DECIMAL(19,4) NOT NULL,
    currency_code       CHAR(3)       NOT NULL DEFAULT 'USD',
    transaction_type    VARCHAR(50)   NOT NULL,
    payment_method      VARCHAR(50),
    reference           VARCHAR(200),
    status              VARCHAR(20)   NOT NULL DEFAULT 'completed',
    transaction_date    TIMESTAMPTZ,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by          UUID
);

CREATE INDEX IF NOT EXISTS idx_transactions_contribution ON transactions(contribution_id) WHERE contribution_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_transactions_invoice      ON transactions(invoice_id)      WHERE invoice_id      IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_transactions_type         ON transactions(transaction_type);

-- Catalogue FK on existing entities. Keep the legacy text columns as snapshot
-- labels so renaming or removing a catalogue row never orphans historical
-- contributions or transactions.
ALTER TABLE scheme_benefits
    ADD COLUMN IF NOT EXISTS benefit_type_id UUID REFERENCES benefit_types(id);

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS transaction_type_id UUID REFERENCES transaction_types(id),
    ADD COLUMN IF NOT EXISTS payment_method_id   UUID REFERENCES payment_methods(id);

-- Seed sensible defaults so a fresh tenant is usable from day one.
INSERT INTO benefit_types (code, label, sort_order) VALUES
    ('OUTPATIENT',  'Outpatient',           10),
    ('INPATIENT',   'Inpatient',            20),
    ('CHRONIC',     'Chronic medication',   30),
    ('DENTAL',      'Dental',               40),
    ('OPTICAL',     'Optical',              50),
    ('MATERNITY',   'Maternity',            60),
    ('EVACUATION',  'Emergency evacuation', 70)
ON CONFLICT (code) DO NOTHING;

INSERT INTO payment_methods (code, label, requires_reference) VALUES
    ('CASH',          'Cash',          FALSE),
    ('BANK_TRANSFER', 'Bank transfer', TRUE),
    ('BANK_DEPOSIT',  'Bank deposit',  TRUE),
    ('CARD',          'Card payment',  TRUE),
    ('MOBILE_MONEY',  'Mobile money',  TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO transaction_types (code, label, sign, requires_approval) VALUES
    ('ORDINARY',   'Ordinary contribution',  '+', FALSE),
    ('ADJUSTMENT', 'Adjustment',             '+', TRUE),
    ('CREDIT',     'Credit (refund)',        '-', TRUE),
    ('DEBIT',      'Debit (charge-back)',    '+', TRUE),
    ('REVERSAL',   'Reversal',               '-', TRUE)
ON CONFLICT (code) DO NOTHING;

INSERT INTO dunning_config (id) VALUES ('00000000-0000-0000-0000-000000000001'::uuid)
    ON CONFLICT (id) DO NOTHING;

INSERT INTO billing_cycle_config (id) VALUES ('00000000-0000-0000-0000-000000000001'::uuid)
    ON CONFLICT (id) DO NOTHING;
