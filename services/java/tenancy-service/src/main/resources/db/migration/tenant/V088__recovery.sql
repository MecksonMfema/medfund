-- =====================================================================
-- V088: Recovery (Phase 10 §A)
-- =====================================================================
-- One row per cession that we expect to recover from a reinsurer.
-- ReinsuranceRecoveryConsumer creates rows in EXPECTED status when the
-- underlying claim is paid; the recoveries-bordereau export flips
-- EXPECTED → INVOICED; the manual forms in Phase 8 handle
-- INVOICED → RECEIVED and → WRITTEN_OFF.
--
-- UNIQUE on cession_id — one recovery per cession, always.

CREATE TABLE recovery (
    id                UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    cession_id        UUID          NOT NULL REFERENCES cession(id) ON DELETE RESTRICT,
    status            VARCHAR(20)   NOT NULL DEFAULT 'EXPECTED',
    expected_amount   DECIMAL(19,4) NOT NULL,
    received_amount   DECIMAL(19,4),
    currency_code     CHAR(3)       NOT NULL,
    invoiced_at       TIMESTAMPTZ,
    received_at       TIMESTAMPTZ,
    write_off_reason  TEXT,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(255),
    CONSTRAINT recovery_status_ck CHECK (status IN ('EXPECTED','INVOICED','RECEIVED','WRITTEN_OFF')),
    CONSTRAINT recovery_cession_uq UNIQUE (cession_id)
);

-- Partial index for the outstanding-recoveries dashboard.
CREATE INDEX ix_recovery_status ON recovery (status)
    WHERE status IN ('EXPECTED','INVOICED');
