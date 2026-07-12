-- =====================================================================
-- V058: per-line adjudication + modifier_codes jsonb → TEXT
-- =====================================================================
-- Adjudicators can accept some lines and reject others on the same
-- claim — the per-line decision needs its own status and (when rejected)
-- a rejection reason. Approved amount already exists on claim_lines.
--
-- modifier_codes is flipped from jsonb → TEXT for the same reason
-- diagnosis_codes / procedure_codes were in V057: the entity binds it
-- as String (comma-separated modifier codes), and any UPDATE that
-- touches a non-null value fails against jsonb.
-- =====================================================================

ALTER TABLE claim_lines
    ADD COLUMN IF NOT EXISTS status           VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN IF NOT EXISTS rejection_reason VARCHAR(50);

ALTER TABLE claim_lines
    ALTER COLUMN modifier_codes TYPE TEXT USING modifier_codes::text;
