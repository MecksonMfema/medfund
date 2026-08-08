-- =====================================================================
-- V067: carry-forward + settlement snapshot on payment_runs
-- =====================================================================
-- A payment run doesn't always settle cleanly: some items get withheld
-- or skipped by tenant rules, and their unpaid balance rolls forward to
-- the next run. The run detail page needs to surface, per run:
--
--   * settlement_date       — when the last item transitioned to paid.
--                             Null until every item in the run is paid.
--   * carried_out_amount    — sum of item amounts still unpaid at the
--                             point the run was executed. Rolls into
--                             the next run's carried_in_amount when the
--                             item-population flow adds those items.
--   * carried_in_amount     — snapshot at run-generation time of the
--                             unpaid balance from prior runs that this
--                             run is intended to settle.
--
-- All three are nullable and default to 0/NULL so existing rows stay
-- valid. The write path for carried_in lands with the future item-
-- population service (nothing populates payment_run_items yet); the
-- write path for carried_out lands in PaymentRunService.execute().
-- settlement_date is refreshed on read from a MAX(payment.paid_at) join
-- when all items are settled — the column is kept as an audit snapshot.
-- =====================================================================

ALTER TABLE payment_runs
    ADD COLUMN IF NOT EXISTS carried_in_amount  numeric(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS carried_out_amount numeric(19, 4) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS settlement_date    timestamptz;
