-- =====================================================================
-- V062: tariff → benefit mapping + scheme annual cap
-- =====================================================================
-- Two related capabilities that only make sense together:
--
-- (a) Per-line benefit resolution. Claims today carry ONE benefit_id
--     at the claim level, so every line deducts from the same
--     beneficiary_benefits row regardless of what tariff it billed.
--     A real multi-service claim (GP consult + blood test + X-ray)
--     should hit Outpatient, Pathology, and Radiology respectively.
--     tariff_benefit_mappings + claim_lines.benefit_id give us the
--     ingestion-time lookup: tariff_code → tariff_codes.category →
--     tariff_benefit_mappings.benefit_type_id → scheme_benefits
--     (scheme_id, benefit_type_id) → the specific scheme_benefit.id.
--     NULL benefit_type_id on the mapping is an explicit "this tariff
--     category deducts from the annual cap only, no per-benefit
--     balance" — used for tariffs that a scheme doesn't want to bucket.
--
-- (b) Scheme-level annual cap. Some tenants set an aggregate ceiling
--     per beneficiary per policy year on top of per-benefit limits.
--     The cap and the sum of benefit limits are independent — a
--     tenant might have $60k of benefit budgets but a $50k cap, or
--     $30k of benefit budgets with $50k of headroom for cap-only
--     tariffs. Every approved amount reduces the cap; some also
--     reduce a per-benefit balance. beneficiary_annual_totals is
--     the dedicated ledger for the cap (derived-sum from
--     beneficiary_benefits would miss cap-only lines).
-- =====================================================================

-- 1) schemes.annual_member_cap ----------------------------------------
-- NULL means no aggregate cap. Only set on multi-benefit insurance
-- lines (medical aid, hospital plans) — single-benefit lines like
-- funeral don't need one.
ALTER TABLE schemes
    ADD COLUMN IF NOT EXISTS annual_member_cap DECIMAL(19, 4);

-- 2) tariff_benefit_mappings ------------------------------------------
-- Small, stable, tenant-configurable. One row per tariff category.
-- NULL benefit_type_id = cap-only (deducts from annual_member_cap,
-- no per-benefit balance touched). Missing row = adjudicator rejects
-- with R03-TARIFF_UNMAPPED (safer than silent fallback).
CREATE TABLE IF NOT EXISTS tariff_benefit_mappings (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tariff_category VARCHAR(64) NOT NULL,
    benefit_type_id UUID REFERENCES benefit_types(id) ON DELETE RESTRICT,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_tariff_benefit_mappings_category UNIQUE (tariff_category)
);

CREATE INDEX IF NOT EXISTS idx_tbm_benefit_type
    ON tariff_benefit_mappings(benefit_type_id)
    WHERE benefit_type_id IS NOT NULL;

-- 3) claim_lines.benefit_id -------------------------------------------
-- Resolved at ingestion. NULL for cap-only lines. Falls back to
-- claim.benefit_id in the adjudicator only for legacy claims that
-- pre-date this migration (their tariff→benefit was implicit in the
-- claim-level pick).
ALTER TABLE claim_lines
    ADD COLUMN IF NOT EXISTS benefit_id UUID REFERENCES scheme_benefits(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_claim_lines_benefit
    ON claim_lines(benefit_id) WHERE benefit_id IS NOT NULL;

-- 4) beneficiary_annual_totals ----------------------------------------
-- One row per (scheme, beneficiary, policy_year) tracking the running
-- total consumed against the scheme's annual_member_cap. Dedicated
-- ledger (not derived from beneficiary_benefits) because cap-only
-- lines have no per-benefit row to sum from. Same NULL-dependant
-- pattern as beneficiary_benefits.
CREATE TABLE IF NOT EXISTS beneficiary_annual_totals (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scheme_id         UUID NOT NULL REFERENCES schemes(id) ON DELETE CASCADE,
    member_id         UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    dependant_id      UUID REFERENCES dependants(id) ON DELETE CASCADE,
    policy_year       INT  NOT NULL,
    consumed_amount   DECIMAL(19, 4) NOT NULL DEFAULT 0,
    currency_code     CHAR(3) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX ux_bat_scheme_beneficiary_year ON beneficiary_annual_totals
    (scheme_id, member_id,
     COALESCE(dependant_id, '00000000-0000-0000-0000-000000000000'::uuid),
     policy_year);

CREATE INDEX idx_bat_member    ON beneficiary_annual_totals(member_id);
CREATE INDEX idx_bat_dependant ON beneficiary_annual_totals(dependant_id)
    WHERE dependant_id IS NOT NULL;

-- 5) claim_line_consumption_log — cap-tracking flag -------------------
-- The idempotency log for the decrement consumer already exists (V061).
-- Add applied_to_cap so a replay knows whether the cap ledger was also
-- touched — makes it clear from the log alone which ledgers a given
-- line updated. Default FALSE so pre-V062 rows read correctly.
ALTER TABLE claim_line_consumption_log
    ADD COLUMN IF NOT EXISTS applied_to_cap BOOLEAN NOT NULL DEFAULT FALSE;
