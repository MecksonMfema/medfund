-- =====================================================================
-- V029: Age-group price history
-- =====================================================================
-- age_groups.contribution_amount + currency_code today is a single
-- value with no history. Editing the price stomps the old one, so a
-- retrospective billing run for May 2026 done today uses today's
-- price instead of May's. That's wrong — once contributions for May
-- are committed at one rate, re-running them at a later rate produces
-- inconsistent statements.
--
-- This migration normalises pricing into age_group_prices, one row
-- per (age_group, effective_from). Editing the price closes the
-- current row (sets effective_to = today - 1) and inserts a new row.
-- The billing query LATERAL-joins to whichever row is active for the
-- run's period.
--
-- The denormalised price columns on age_groups stay as the "current
-- price" cache so existing read paths (UI listing, scheme rollups)
-- keep working without a refactor. The edit workflow updates them in
-- step with the latest active price row.
-- =====================================================================

CREATE TABLE IF NOT EXISTS age_group_prices (
    id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    age_group_id        UUID NOT NULL REFERENCES age_groups(id) ON DELETE CASCADE,
    contribution_amount NUMERIC(19,4) NOT NULL,
    currency_code       CHAR(3)       NOT NULL,

    -- effective_from is inclusive; effective_to is inclusive. NULL
    -- effective_to means "currently active". A billing period whose
    -- end date falls within [effective_from, effective_to] (or after
    -- effective_from when effective_to is NULL) matches.
    effective_from      DATE          NOT NULL,
    effective_to        DATE          NULL,

    created_at          TIMESTAMPTZ   NOT NULL DEFAULT NOW(),
    created_by          UUID          NULL,

    CONSTRAINT age_group_prices_range CHECK (
        effective_to IS NULL OR effective_to >= effective_from
    )
);

CREATE INDEX IF NOT EXISTS idx_age_group_prices_active
    ON age_group_prices(age_group_id)
    WHERE effective_to IS NULL;

CREATE INDEX IF NOT EXISTS idx_age_group_prices_lookup
    ON age_group_prices(age_group_id, effective_from);

-- Backfill: one row per existing age_group, sourcing the amount /
-- currency / effective_from from the existing denormalised state.
-- effective_to stays NULL — every existing price is "currently
-- active" until a tenant edits it post-migration.
INSERT INTO age_group_prices (age_group_id, contribution_amount, currency_code, effective_from)
SELECT id, contribution_amount, currency_code, created_at::date
  FROM age_groups
 WHERE NOT EXISTS (
     SELECT 1 FROM age_group_prices p WHERE p.age_group_id = age_groups.id
 );
