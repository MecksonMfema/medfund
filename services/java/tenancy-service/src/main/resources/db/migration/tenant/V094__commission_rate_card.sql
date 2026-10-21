-- =====================================================================
-- V094: Commission rate card (Phase 11 §A P5 — hybrid engine base half)
-- =====================================================================
-- Lookup table for the base commission rate. Kickers on top come from
-- COMMISSION-category rules (PAY_COMMISSION action). Producer_tier is
-- nullable — NULL is the default catch-all card for a given line.
-- clawback_window_days null = never clawback via member lapse.
-- =====================================================================

CREATE TABLE commission_rate_card (
    id                     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    name                   VARCHAR(120)  NOT NULL,
    insurance_line         VARCHAR(20)   NOT NULL,
    producer_tier          VARCHAR(40),
    base_rate_pct          NUMERIC(7,4)  NOT NULL,
    clawback_window_days   INT,
    effective_from         DATE          NOT NULL,
    effective_to           DATE,
    is_active              BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id               UUID,
    actor_email            VARCHAR(255),
    CONSTRAINT rc_rate_ck            CHECK (base_rate_pct >= 0 AND base_rate_pct <= 100),
    CONSTRAINT rc_period_ck          CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT rc_clawback_window_ck CHECK (clawback_window_days IS NULL
                                            OR clawback_window_days BETWEEN 0 AND 3650),
    CONSTRAINT rc_line_ck CHECK (insurance_line IN
        ('HEALTH','LIFE','FUNERAL','GROUP','TRAVEL','DISABILITY','VEHICLE','PROPERTY'))
);

CREATE INDEX ix_rate_card_lookup ON commission_rate_card
    (insurance_line, producer_tier, effective_from, effective_to);
CREATE INDEX ix_rate_card_active ON commission_rate_card (name) WHERE is_active = TRUE;
