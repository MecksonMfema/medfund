-- =====================================================================
-- V166: PMB (Prescribed Minimum Benefit) classification columns on claims.
--
-- Phase 16 §B REG7 — Council for Medical Schemes reporting requires each
-- claim to be classified as PMB or non-PMB, with the matched condition
-- code preserved for the PMB spend return. Two nullable-defaulting columns
-- so historical rows remain valid while the backfill batch job (Phase 17
-- rules seed + this-phase PmbBackfillJob) walks the table and writes
-- classifications.
--
-- Design notes:
--   * is_pmb defaults FALSE — a claim is not PMB until a rule explicitly
--     matches it. Making it NOT NULL keeps downstream aggregation predicates
--     simple (SUM(...) WHERE is_pmb = TRUE).
--   * pmb_condition_code stays NULL for non-PMB claims. VARCHAR(20) fits
--     both the CMS 4-digit condition codes and the ICD-10 fallback code
--     variants used in edge templates.
--   * The partial index only carries rows where is_pmb = TRUE so the
--     PMB spend report can scan a tiny slice even on schemes with millions
--     of non-PMB claims.
-- =====================================================================

ALTER TABLE claims
    ADD COLUMN IF NOT EXISTS is_pmb             BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS pmb_condition_code VARCHAR(20) NULL;

CREATE INDEX IF NOT EXISTS ix_claims_pmb
    ON claims (is_pmb, pmb_condition_code)
    WHERE is_pmb = TRUE;

COMMENT ON COLUMN claims.is_pmb IS
    'TRUE when a PMB classification rule matched the claim. Feeds the PMB_SPEND regulator report (Phase 16 §B REG7). Populated at adjudication time by RuleCategory.PMB_CLASSIFICATION (Phase 17) and by PmbBackfillJob for historical rows.';
COMMENT ON COLUMN claims.pmb_condition_code IS
    'The CMS PMB condition code (or ICD-10 fallback) that classified the claim as PMB. NULL when is_pmb = FALSE. VARCHAR(20) sizes for both the 4-digit CMS codes and longer ICD-10 fallbacks.';
