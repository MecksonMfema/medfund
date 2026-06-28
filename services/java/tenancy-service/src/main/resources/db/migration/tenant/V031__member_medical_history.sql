-- =====================================================================
-- V031: Member medical history (data-driven pricing signals)
-- =====================================================================
-- Phase B: the rules engine and AI scorer need member-level signals
-- beyond what the existing member/dependant columns expose. Rather
-- than minting a dozen typed columns we'd never query independently,
-- a single JSONB column holds whatever the underwriting flow
-- captures.
--
-- ContributionFactBuilder reads this and projects the salient fields
-- (chronic condition count, smoking status, BMI band) into
-- ContributionFact so a Drools rule template like
--   "if chronicConditionCount >= 2 then amount = base * 1.3"
-- can fire per-contribution. The AI service in turn consumes the
-- same fact shape to compute a risk multiplier.
--
-- Schema is JSONB so the catalogue of captured signals can evolve
-- without further migrations. Reading a missing key just returns
-- null, which the fact builder treats as "unknown".
--
-- Suggested keys (convention, not enforced):
--   chronic_conditions  TEXT[]    — codes like 'DIABETES', 'HYPERTENSION'
--   smoking_status      TEXT      — 'NEVER' | 'FORMER' | 'CURRENT'
--   bmi                 NUMERIC   — body mass index
--   medication_count    INTEGER
--   last_screened_at    TIMESTAMP
-- =====================================================================

ALTER TABLE members
    ADD COLUMN IF NOT EXISTS medical_history JSONB NULL;

-- Lookups will be sparse for now (the rule engine reads the whole blob
-- via the fact builder), but a partial GIN index on the
-- chronic_conditions array keeps "members with diabetes" queries fast
-- when the underwriting reports surface them.
CREATE INDEX IF NOT EXISTS idx_members_chronic_conditions
    ON members USING GIN ((medical_history->'chronic_conditions'))
    WHERE medical_history IS NOT NULL;
