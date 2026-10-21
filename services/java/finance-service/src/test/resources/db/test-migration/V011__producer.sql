-- Test-migration layer for Phase 11 producer / broker module. Mirrors the
-- production tenant migrations V092..V094 (in tenancy-service) plus the
-- subset of V095..V098 that Phase 2 ITs actually exercise (rate cards +
-- assignments; the commission_transaction / clawback / adjustment /
-- backfill_candidate tables land in later phase test migrations when
-- their consumers arrive).

CREATE TABLE producer (
    id                     UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    producer_code          VARCHAR(40)   NOT NULL,
    name                   VARCHAR(200)  NOT NULL,
    contact_email          VARCHAR(255),
    contact_phone          VARCHAR(40),
    jurisdiction_code      VARCHAR(20),
    home_currency          CHAR(3)       NOT NULL,
    parent_producer_id     UUID          REFERENCES producer(id) ON DELETE RESTRICT,
    wht_pct_override       NUMERIC(5,2),
    banking_details        JSONB,
    is_active              BOOLEAN       NOT NULL DEFAULT TRUE,
    activated_at           TIMESTAMPTZ,
    terminated_at          TIMESTAMPTZ,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    actor_id               UUID,
    actor_email            VARCHAR(255),
    CONSTRAINT producer_code_uq            UNIQUE (producer_code),
    CONSTRAINT producer_no_self_parent_ck  CHECK (parent_producer_id IS DISTINCT FROM id),
    CONSTRAINT producer_wht_range_ck       CHECK (wht_pct_override IS NULL
                                                  OR (wht_pct_override >= 0 AND wht_pct_override <= 100))
);
CREATE INDEX ix_producer_parent      ON producer (parent_producer_id) WHERE parent_producer_id IS NOT NULL;
CREATE INDEX ix_producer_home_ccy    ON producer (home_currency);
CREATE INDEX ix_producer_active_name ON producer (name)              WHERE is_active = TRUE;

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
CREATE UNIQUE INDEX ux_mpa_one_open_per_member
    ON member_producer_assignment (member_id) WHERE effective_to IS NULL;
CREATE INDEX ix_mpa_producer_open ON member_producer_assignment (producer_id)
    WHERE effective_to IS NULL;
CREATE INDEX ix_mpa_member        ON member_producer_assignment (member_id, effective_from);

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

GRANT SELECT, INSERT, UPDATE, DELETE, REFERENCES, TRIGGER
    ON ALL TABLES IN SCHEMA public TO public_role;
