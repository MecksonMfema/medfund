-- Test-migration layer for Phase 2 (V069 in production): member_payables +
-- member_payable_applications, plus the new ctc_payments lifecycle columns.
-- Lives alongside V001__ctc.sql; the same public_role role gets the
-- CRUD grant at the tail so the tenant-scoped connection can read/write
-- both tables.

CREATE TABLE member_payables (
    id              UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id       UUID           NOT NULL,
    claim_id        UUID           NOT NULL,
    claim_number    TEXT,
    amount          NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    currency_code   VARCHAR(3)     NOT NULL,
    status          TEXT           NOT NULL DEFAULT 'open',
    recorded_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    recorded_by     UUID,
    CONSTRAINT member_payables_status_check
        CHECK (status IN ('open', 'applied', 'reversed')),
    CONSTRAINT member_payables_claim_unique UNIQUE (claim_id)
);

CREATE TABLE member_payable_applications (
    id                   UUID           PRIMARY KEY DEFAULT gen_random_uuid(),
    member_payable_id    UUID           NOT NULL REFERENCES member_payables(id),
    source_type          TEXT           NOT NULL,
    source_id            UUID           NOT NULL,
    amount_applied       NUMERIC(19, 4) NOT NULL,
    currency_code        VARCHAR(3)     NOT NULL,
    applied_at           TIMESTAMPTZ    NOT NULL DEFAULT now(),
    applied_by           UUID,
    CONSTRAINT mpa_amount_nonzero CHECK (amount_applied <> 0),
    CONSTRAINT mpa_source_type_check CHECK (source_type IN ('CTC'))
);

-- Lifecycle columns on ctc_payments (production V069).
ALTER TABLE ctc_payments
    ADD COLUMN type              TEXT        NOT NULL DEFAULT 'CTC',
    ADD COLUMN status            TEXT        NOT NULL DEFAULT 'draft',
    ADD COLUMN member_payable_id UUID REFERENCES member_payables(id),
    ADD COLUMN reverses_ctc_id   UUID,
    ADD COLUMN committed_at      TIMESTAMPTZ,
    ADD COLUMN committed_by      UUID;

-- Refresh the tenant role's grants so it picks up the new tables.
GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
