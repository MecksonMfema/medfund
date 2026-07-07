-- =====================================================================
-- V052: waiting_period_rules.min_age / max_age (age-scoped waiting)
-- =====================================================================
-- Lets operators say "GENERAL waiting is 90 days for under-65s but 730
-- days for 65+". When either bound is set, AdjudicationPipeline Stage 2
-- prefers the age-scoped rule over the plain per-(scheme,condition_type)
-- rule when the member's age at claim time falls in the band.
--
-- Both nullable. Existing rows keep their existing semantics (apply to
-- everyone in the scheme). CHECK enforces the human 0..120 clamp and the
-- min <= max order same as V050 / V051.
-- =====================================================================

ALTER TABLE waiting_period_rules
    ADD COLUMN IF NOT EXISTS min_age SMALLINT NULL
        CHECK (min_age IS NULL OR (min_age >= 0 AND min_age <= 120)),
    ADD COLUMN IF NOT EXISTS max_age SMALLINT NULL
        CHECK (max_age IS NULL OR (max_age >= 0 AND max_age <= 120));

ALTER TABLE waiting_period_rules
    ADD CONSTRAINT waiting_period_rules_age_range_order
        CHECK (min_age IS NULL OR max_age IS NULL OR min_age <= max_age);
