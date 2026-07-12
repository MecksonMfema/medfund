-- =====================================================================
-- V057: diagnosis_codes / procedure_codes jsonb → TEXT
-- =====================================================================
-- The entity binds these as String (comma-separated ICD-10 / CPT codes)
-- and the front-end sends them that way too. The columns were jsonb in
-- the V014 baseline, which meant every UPDATE that carried a non-null
-- value into either column failed with "expression is of type character
-- varying". Convert to TEXT so the entity type matches the storage.
--
-- Existing rows (if any) survive via ::text — jsonb serialises to its
-- textual JSON representation, which is at worst readable if not
-- exactly the comma-separated shape the entity now writes.
-- =====================================================================

ALTER TABLE claims
    ALTER COLUMN diagnosis_codes TYPE TEXT USING diagnosis_codes::text,
    ALTER COLUMN procedure_codes TYPE TEXT USING procedure_codes::text;
