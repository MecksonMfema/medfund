-- =====================================================================
-- V092: Producer registry (Phase 11 §A)
-- =====================================================================
-- Root of the producer/broker module — self-referential hierarchy via
-- parent_producer_id (no time-slicing per P8; reparenting rewrites the
-- field, audit trail preserved via AuditEvent). home_currency is NOT NULL
-- because payout FX is locked at commit time against this currency
-- (see .claude/multi-currency.md:164, plan §A P10).
-- =====================================================================

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
