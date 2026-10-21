-- =====================================================================
-- V077: Additive cost-share amount columns on claims + claim_lines + quotations.
--
-- Phase 2 of the copayments standard flow. All columns are NULLABLE —
-- historical rows keep NULL. approvedAmount preserves its existing
-- meaning ("plan-paid"); the new columns break it down per G3:
--
--   allowedAmount        = ruleAdjustedTotal (post modifiers)
--   deductibleApplied    = amount routed to member deductible
--   copayAmount          = flat/percent copay (benefit-level or rule-override)
--   coinsuranceAmount    = post-deductible coinsurance
--   notCoveredAmount     = benefit rejected portion
--   shortfallAmount      = AHFOZ shortfall (claimed - tariff)
--   memberResponsibility = deductible + copay + coinsurance + notCovered
--                          [+ shortfall if scheme_cost_share.shortfall_policy = RECOVER_FROM_MEMBER]
--
-- Quotations gain computedCoPaymentAmount so the review UI can warn when
-- the operator's override diverges from the auto-computed value; both
-- numbers are persisted for the audit trail.
--
-- See feedback_never_edit_applied_migrations — never mutate this file
-- after it applies; ship corrections as V078+.
-- =====================================================================

ALTER TABLE claims
    ADD COLUMN allowed_amount         DECIMAL(19,4),
    ADD COLUMN deductible_applied     DECIMAL(19,4),
    ADD COLUMN copay_amount           DECIMAL(19,4),
    ADD COLUMN coinsurance_amount     DECIMAL(19,4),
    ADD COLUMN not_covered_amount     DECIMAL(19,4),
    ADD COLUMN shortfall_amount       DECIMAL(19,4),
    ADD COLUMN member_responsibility  DECIMAL(19,4);

ALTER TABLE claim_lines
    ADD COLUMN allowed_amount         DECIMAL(19,4),
    ADD COLUMN deductible_applied     DECIMAL(19,4),
    ADD COLUMN copay_amount           DECIMAL(19,4),
    ADD COLUMN coinsurance_amount     DECIMAL(19,4),
    ADD COLUMN not_covered_amount     DECIMAL(19,4),
    ADD COLUMN shortfall_amount       DECIMAL(19,4),
    ADD COLUMN member_responsibility  DECIMAL(19,4);

ALTER TABLE quotations
    ADD COLUMN computed_copay_amount  DECIMAL(19,4);
