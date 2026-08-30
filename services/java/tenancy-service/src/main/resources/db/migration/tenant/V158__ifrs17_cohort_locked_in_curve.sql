-- Phase 15 §6 (I18): widen ifrs17_cohort with the locked-in yield curve snapshot
-- + the timestamp the lock happened. Both are nullable — populated on first
-- policy issuance into the cohort by Ifrs17CohortLockInService, and by the
-- V159 backfill for cohorts that already carry policies.
--
-- IFRS 17.44 requires the discount curve at initial recognition of a group of
-- contracts to be locked in for CSM interest accretion (GMM) — even when the
-- current curve moves. Storing the whole shape as JSONB lets the report path
-- interpolate to arbitrary tenors without a second lookup.

ALTER TABLE ifrs17_cohort
    ADD COLUMN IF NOT EXISTS locked_in_yield_curve_snapshot JSONB NULL,
    ADD COLUMN IF NOT EXISTS locked_in_at TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS ix_ifrs17_cohort_locked_in
    ON ifrs17_cohort (locked_in_at) WHERE locked_in_at IS NOT NULL;
