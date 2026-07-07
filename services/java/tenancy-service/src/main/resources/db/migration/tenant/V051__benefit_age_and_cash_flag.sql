-- =====================================================================
-- V051: scheme_benefits.min_age / max_age / cash_claim_allowed
-- =====================================================================
-- Benefit-level age gates + payout-mode flag. Two axes:
--
--   min_age / max_age (both nullable):
--     When set, adjudication Stage 3 (Benefit Limit) rejects claims for
--     members outside the range with AGE_OUT_OF_RANGE. Common patterns:
--       - "Preventive care benefit is 40+ only" → min_age = 40
--       - "Youth dental plan tops out at 17" → max_age = 17
--
--   cash_claim_allowed (default true, NOT NULL):
--     When false, adjudication Stage 3 rejects claims whose payout_mode
--     is CASH with IN_KIND_ONLY. Typical use: funeral / long-term care
--     benefits that must pay the provider directly, never the member.
--     Defaults to true so existing benefits keep their current behaviour.
-- =====================================================================

ALTER TABLE scheme_benefits
    ADD COLUMN IF NOT EXISTS min_age SMALLINT NULL
        CHECK (min_age IS NULL OR (min_age >= 0 AND min_age <= 120)),
    ADD COLUMN IF NOT EXISTS max_age SMALLINT NULL
        CHECK (max_age IS NULL OR (max_age >= 0 AND max_age <= 120)),
    ADD COLUMN IF NOT EXISTS cash_claim_allowed BOOLEAN NOT NULL DEFAULT TRUE;

ALTER TABLE scheme_benefits
    ADD CONSTRAINT scheme_benefits_age_range_order
        CHECK (min_age IS NULL OR max_age IS NULL OR min_age <= max_age);
