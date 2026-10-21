-- =====================================================================
-- V080: Balance snapshots (Phase 6 — grilled D6-1..D6-8)
--
-- Freeze-frame of provider / member balances at each executed payment
-- run, so any past run's creditor state is reproducible even though the
-- live provider_balances / member_balances keep moving on claim
-- adjudication, CTC commits, advance drawdowns and mark-paid events.
--
-- Per D6-1 the snapshot is a pure freeze-frame: opening_balance =
-- closing_balance = outstanding_balance as it stood at run execution.
-- net_due carries the run's payout for that payee (from its advice).
-- Per D6-2 only payees present in the run's items get a row.
-- Per D6-3 rows are written inside the run's transaction (hard-fail).
--
-- No backfill (D6-8): history starts at the next executed run.
-- =====================================================================

CREATE TABLE IF NOT EXISTS provider_balance_snapshot (
    id                  uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_run_id      uuid           NOT NULL,
    provider_id         uuid           NOT NULL,
    currency_code       varchar(3)     NOT NULL,
    opening_balance     numeric(19,4)  NOT NULL DEFAULT 0,
    closing_balance     numeric(19,4)  NOT NULL DEFAULT 0,
    total_claimed       numeric(19,4)  NOT NULL DEFAULT 0,
    total_approved      numeric(19,4)  NOT NULL DEFAULT 0,
    total_paid          numeric(19,4)  NOT NULL DEFAULT 0,
    net_due             numeric(19,4)  NOT NULL DEFAULT 0,
    taken_at            timestamptz    NOT NULL,
    CONSTRAINT uq_provider_balance_snapshot UNIQUE (payment_run_id, provider_id, currency_code)
);

CREATE INDEX IF NOT EXISTS idx_provider_balance_snapshot_provider
    ON provider_balance_snapshot(provider_id, taken_at DESC);
CREATE INDEX IF NOT EXISTS idx_provider_balance_snapshot_run
    ON provider_balance_snapshot(payment_run_id);

CREATE TABLE IF NOT EXISTS member_balance_snapshot (
    id                  uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_run_id      uuid           NOT NULL,
    member_id           uuid           NOT NULL,
    currency_code       varchar(3)     NOT NULL,
    opening_balance     numeric(19,4)  NOT NULL DEFAULT 0,
    closing_balance     numeric(19,4)  NOT NULL DEFAULT 0,
    total_claimed       numeric(19,4)  NOT NULL DEFAULT 0,
    total_approved      numeric(19,4)  NOT NULL DEFAULT 0,
    total_paid          numeric(19,4)  NOT NULL DEFAULT 0,
    net_due             numeric(19,4)  NOT NULL DEFAULT 0,
    taken_at            timestamptz    NOT NULL,
    CONSTRAINT uq_member_balance_snapshot UNIQUE (payment_run_id, member_id, currency_code)
);

CREATE INDEX IF NOT EXISTS idx_member_balance_snapshot_member
    ON member_balance_snapshot(member_id, taken_at DESC);
CREATE INDEX IF NOT EXISTS idx_member_balance_snapshot_run
    ON member_balance_snapshot(payment_run_id);
