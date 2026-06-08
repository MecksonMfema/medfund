-- Bind each scheme to exactly one insurance product line so the operational
-- UI and API can gate person-only features (age groups, scheme benefits) for
-- asset-centric lines like VEHICLE and PROPERTY where they have no actuarial
-- meaning. The line is derived from scheme_type using the same mapping the
-- Angular catalogue exposes in core/models/insurance-lines.ts
-- (SCHEME_TYPES_BY_LINE). Any unknown / NULL scheme_type falls back to
-- HEALTH, matching the existing `medical_aid` column default.
--
-- Any pre-existing age_groups or scheme_benefits attached to a scheme that
-- backfills to VEHICLE/PROPERTY remain as historical rows — the operational
-- UI hides the screens for those schemes; no destructive cleanup is performed
-- by this migration.

ALTER TABLE schemes
    ADD COLUMN IF NOT EXISTS insurance_line VARCHAR(32);

UPDATE schemes
SET insurance_line = CASE scheme_type
    -- HEALTH
    WHEN 'medical_aid'      THEN 'HEALTH'
    WHEN 'health_insurance' THEN 'HEALTH'
    WHEN 'hmo'              THEN 'HEALTH'
    WHEN 'wellness'         THEN 'HEALTH'
    WHEN 'hospital_cash'    THEN 'HEALTH'
    -- LIFE
    WHEN 'term_life'        THEN 'LIFE'
    WHEN 'whole_life'       THEN 'LIFE'
    WHEN 'endowment'        THEN 'LIFE'
    -- VEHICLE
    WHEN 'comprehensive'    THEN 'VEHICLE'
    WHEN 'third_party'      THEN 'VEHICLE'
    WHEN 'fleet'            THEN 'VEHICLE'
    -- FUNERAL
    WHEN 'funeral_benefit'  THEN 'FUNERAL'
    WHEN 'family_funeral'   THEN 'FUNERAL'
    -- PROPERTY
    WHEN 'buildings'        THEN 'PROPERTY'
    WHEN 'contents'         THEN 'PROPERTY'
    WHEN 'all_risk'         THEN 'PROPERTY'
    -- GROUP
    WHEN 'group_health'     THEN 'GROUP'
    WHEN 'group_life'       THEN 'GROUP'
    WHEN 'group_disability' THEN 'GROUP'
    -- TRAVEL
    WHEN 'single_trip'      THEN 'TRAVEL'
    WHEN 'multi_trip'       THEN 'TRAVEL'
    WHEN 'annual_travel'    THEN 'TRAVEL'
    -- DISABILITY
    WHEN 'short_term_disability' THEN 'DISABILITY'
    WHEN 'long_term_disability'  THEN 'DISABILITY'
    WHEN 'income_protection'     THEN 'DISABILITY'
    ELSE 'HEALTH'
END
WHERE insurance_line IS NULL;

ALTER TABLE schemes
    ALTER COLUMN insurance_line SET NOT NULL;

ALTER TABLE schemes
    ALTER COLUMN insurance_line SET DEFAULT 'HEALTH';

CREATE INDEX IF NOT EXISTS idx_schemes_insurance_line ON schemes(insurance_line, status);
