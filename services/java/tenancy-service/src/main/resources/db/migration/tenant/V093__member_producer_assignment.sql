-- =====================================================================
-- V093: Time-slice member↔producer assignments (Phase 11 §A P2)
-- =====================================================================
-- Effective_from is the 1st-of-month (app-layer snap per
-- feedback_effective_date_snap). Effective_to is exclusive; NULL = open.
-- Partial UNIQUE index enforces at-most-one-open-per-member (V048 precedent).
-- =====================================================================

CREATE TABLE member_producer_assignment (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id         UUID         NOT NULL,
    producer_id       UUID         NOT NULL REFERENCES producer(id) ON DELETE RESTRICT,
    effective_from    DATE         NOT NULL,
    effective_to      DATE,
    change_reason     VARCHAR(120),
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    actor_id          UUID,
    actor_email       VARCHAR(255),
    CONSTRAINT mpa_period_ck CHECK (effective_to IS NULL OR effective_to >= effective_from)
);

-- One open assignment per member (partial UNIQUE per V048 precedent).
CREATE UNIQUE INDEX ux_mpa_one_open_per_member
    ON member_producer_assignment (member_id) WHERE effective_to IS NULL;

CREATE INDEX ix_mpa_producer_open ON member_producer_assignment (producer_id)
    WHERE effective_to IS NULL;
CREATE INDEX ix_mpa_member        ON member_producer_assignment (member_id, effective_from);
