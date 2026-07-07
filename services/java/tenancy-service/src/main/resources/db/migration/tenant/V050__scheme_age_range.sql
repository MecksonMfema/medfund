-- =====================================================================
-- V050: schemes.min_age / max_age for enrolment eligibility gates
-- =====================================================================
-- Insurers treat older members very differently across lines: HEALTH may
-- cap enrolment at 65, FUNERAL may accept over-65s with a long waiting
-- period, LIFE may load the premium. This adds the "hard cap" pair —
-- if a scheme sets either bound, enrolment rejects with 422 when the
-- member's age at enrolment falls outside the range.
--
-- Rules-engine templates (AgeGroupTemplates) handle the softer patterns
-- (premium loading, extended waiting, no cash claims) via APPLY_LOADED_PREMIUM
-- and REJECT actions gated on member.age.
--
-- Both nullable — asset-centric schemes (VEHICLE, PROPERTY) leave both
-- null and existing person-centric schemes without a cap keep working.
-- CHECK constraint clamps to human range so operators can't fat-finger
-- age=999 into an eligibility gate.
-- =====================================================================

ALTER TABLE schemes
    ADD COLUMN IF NOT EXISTS min_age SMALLINT NULL
        CHECK (min_age IS NULL OR (min_age >= 0 AND min_age <= 120)),
    ADD COLUMN IF NOT EXISTS max_age SMALLINT NULL
        CHECK (max_age IS NULL OR (max_age >= 0 AND max_age <= 120));

-- Also enforce range order at the DB — if both are set, min <= max.
ALTER TABLE schemes
    ADD CONSTRAINT schemes_age_range_order
        CHECK (min_age IS NULL OR max_age IS NULL OR min_age <= max_age);
