-- =====================================================================
-- V033: schemes.default_premium for non-HEALTH STANDARD pricing
-- =====================================================================
-- HEALTH schemes price each candidate via the age_groups + age_group_prices
-- pair. The six new lines (MOTOR, PROPERTY, LIFE, FUNERAL, TRAVEL,
-- DISABILITY) have no age-group concept; the plan's Part 2 spec
-- (see Out of scope notes) is:
--
--   "scheme-default pricing for non-HEALTH lines comes from a flat
--    default_premium column on each asset's parent scheme or from the
--    AI multiplier"
--
-- This adds that column. The per-line CandidateResolvers (Part 3)
-- project COALESCE(billing_override_amount, scheme.default_premium, 0)
-- into PersonCandidate.priceAmount. The AI_DRIVEN pricing mode then
-- multiplies by the per-candidate risk multiplier.
--
-- Nullable so existing HEALTH schemes don't fail the migration and so
-- the seed/import path stays lenient. Tenants who haven't set a
-- default_premium for a non-HEALTH scheme will see a zero-priced
-- preview row — surfaces the data gap loudly rather than guessing.
-- =====================================================================

ALTER TABLE schemes
    ADD COLUMN IF NOT EXISTS default_premium NUMERIC(19,4) NULL
        CHECK (default_premium IS NULL OR default_premium >= 0);
