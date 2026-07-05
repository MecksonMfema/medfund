-- V046 — Dependant deactivation model.
--
-- Business rule (user-confirmed 2026-07-05): dependants are never
-- deleted, only DEACTIVATED with an effective date. Rationale:
--   * Historical claims + billing rows FK to dependant_id — hard-
--     deletion would orphan the audit trail.
--   * The exact date a dependant left cover matters for pro-rata
--     refunds and downstream compliance.
--   * A "deactivated" row can be re-activated (rare, but possible
--     for administrative corrections).
--
-- Migration steps:
--   1. Add deactivation_effective_date column.
--   2. Backfill: rows currently at status='removed' are treated as
--      historically deactivated. Their effective date is best-guess
--      from updated_at::date (the audit trail's UPDATE timestamp).
--      New rows going forward use the operator-picked date.
--   3. Rename status 'removed' → 'deactivated' for consistency.

ALTER TABLE dependants
    ADD COLUMN deactivation_effective_date DATE;

COMMENT ON COLUMN dependants.deactivation_effective_date IS
    'Date the deactivation takes effect. Billing continues UP TO AND INCLUDING the cycle that contains this date; the resolver excludes the dependant from cycles starting after this date. See HealthCandidateResolver.';

-- Backfill: any row already at 'removed' becomes 'deactivated' with
-- effective_date = updated_at::date. If updated_at is null, use
-- current date so the record remains billable-until-today, not
-- indefinitely (an unbounded null would silently exclude them from
-- retroactive projections).
UPDATE dependants
   SET status = 'deactivated',
       deactivation_effective_date = COALESCE(updated_at::date, CURRENT_DATE)
 WHERE status = 'removed';
