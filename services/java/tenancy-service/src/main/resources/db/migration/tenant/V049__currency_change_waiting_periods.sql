-- =====================================================================
-- V049: Direction-specific currency-change waiting periods
-- =====================================================================
-- Companion to V011 scheme_change_waiting_period_rules (per-benefit
-- upgrade/downgrade waits). Currency-change waits are asymmetric —
-- a tenant may want ZWG → USD to sit through a 90-day wait but let
-- USD → ZWG effect immediately. V011's per-change_type shape can't
-- express that; this table keys on the (from_currency, to_currency)
-- pair explicitly.
--
-- SchemeChangeService.resolveEffectiveDate consults this table when
-- change_kind = CURRENCY_CHANGE. If a matching (from, to) row exists
-- and is_active, the requester's effective_date is auto-pushed
-- forward to today + waiting_days (snapped to the 1st of the resulting
-- month by the caller). No matching row → no wait.
--
-- from_currency and to_currency are CHAR(3) ISO codes, uppercased.
-- Uniqueness on (from, to) so a tenant can't have two contradictory
-- rules for the same direction.
--
-- Idempotent (IF NOT EXISTS) so Flyway can rerun the migration if
-- needed (feedback_never_edit_applied_migrations).

CREATE TABLE IF NOT EXISTS currency_change_waiting_period_rules (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    from_currency  CHAR(3)     NOT NULL,
    to_currency    CHAR(3)     NOT NULL,
    waiting_days   INTEGER     NOT NULL CHECK (waiting_days >= 0),
    description    TEXT,
    is_active      BOOLEAN     NOT NULL DEFAULT TRUE,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ck_currency_change_direction_not_self
        CHECK (from_currency <> to_currency)
);

CREATE UNIQUE INDEX IF NOT EXISTS ux_currency_change_direction
    ON currency_change_waiting_period_rules(from_currency, to_currency);

CREATE INDEX IF NOT EXISTS idx_currency_change_active
    ON currency_change_waiting_period_rules(from_currency, to_currency)
    WHERE is_active = TRUE;

COMMENT ON TABLE currency_change_waiting_period_rules IS
    'Direction-specific waiting periods for scheme changes that cross currencies. Zimbabwe-style example: (ZWG, USD, 90) waits three months moving into USD; the reverse (USD, ZWG) has no row so the change effects immediately.';
