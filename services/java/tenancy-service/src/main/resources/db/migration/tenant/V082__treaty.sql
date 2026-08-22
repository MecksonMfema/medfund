-- =====================================================================
-- V082: Treaty (Phase 10 §A)
-- =====================================================================
-- Each treaty is one contract with one or more reinsurers (participants
-- ship in V084). Layers ship in V083 for XoL / StopLoss shapes.
--
-- renewed_from_treaty_id lets us walk a renewal chain (the current row
-- points at its predecessor); activation of a successor is what moves
-- the predecessor to RENEWED.

CREATE TABLE treaty (
    id                        UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    treaty_ref                VARCHAR(120)  NOT NULL,
    treaty_type               VARCHAR(20)   NOT NULL,
    declared_currency         CHAR(3)       NOT NULL,
    inception_date            DATE          NOT NULL,
    expiry_date               DATE          NOT NULL,
    status                    VARCHAR(20)   NOT NULL DEFAULT 'DRAFT',
    renewed_from_treaty_id    UUID          REFERENCES treaty(id) ON DELETE RESTRICT,
    aggregate_limit           DECIMAL(19,4),
    aggregate_limit_currency  CHAR(3),
    expected_annual_premium   DECIMAL(19,4),
    producer_ref              VARCHAR(120),
    activated_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id                  UUID,
    actor_email               VARCHAR(255),
    CONSTRAINT treaty_type_ck   CHECK (treaty_type IN ('QUOTA_SHARE','SURPLUS_SHARE','EXCESS_OF_LOSS','STOP_LOSS')),
    CONSTRAINT treaty_status_ck CHECK (status IN ('DRAFT','ACTIVE','EXPIRED','RENEWED','LAPSED','COMMUTED')),
    CONSTRAINT treaty_period_ck CHECK (expiry_date > inception_date)
);

CREATE UNIQUE INDEX ux_treaty_ref ON treaty (treaty_ref);
CREATE INDEX ix_treaty_status_period ON treaty (status, inception_date, expiry_date);
