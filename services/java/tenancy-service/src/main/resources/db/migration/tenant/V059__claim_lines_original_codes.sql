-- =====================================================================
-- V059: preserve operator-captured tariff / modifier codes on claim_lines
-- =====================================================================
-- The adjudicator can now edit the tariff code and modifiers on a
-- claim line (typo fixes, code corrections, etc.). To keep the
-- capture record honest — so we can always answer "what did the
-- claimant actually submit?" — snapshot the operator's originals into
-- these columns the first time the adjudicator changes either value.
--
-- Both nullable: unchanged lines keep original_* NULL and the current
-- tariff_code / modifier_codes columns remain the source of truth.
-- =====================================================================

ALTER TABLE claim_lines
    ADD COLUMN IF NOT EXISTS original_tariff_code   VARCHAR(20),
    ADD COLUMN IF NOT EXISTS original_modifier_codes TEXT;
