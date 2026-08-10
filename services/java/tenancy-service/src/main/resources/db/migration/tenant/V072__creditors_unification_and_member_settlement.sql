-- =====================================================================
-- V072: Creditors unification + member settlement
--
-- Ships three additive schema changes and one backfill:
--   1. member_balances — snapshot table mirroring provider_balances
--      shape; source of truth for the finance-side Creditors listing
--      MEMBER rows.
--   2. payment_runs.payee_type — NOT NULL column enforcing homogeneous
--      runs (per grilling decision G4). Trigger ensures every child
--      payment_run_item's payee_type matches the parent.
--   3. member_payable_applications.source_type — extend CHECK to accept
--      'PAYMENT' (was 'CTC' only per V069); required by Phase 3's
--      Payment.markPaid FIFO application path.
--   4. Backfill member_balances from claims + member_payables +
--      member_payable_applications (per grilling decision G8c —
--      full backfill).
--
-- Tenant-schema table names throughout — never prefixed public. to
-- avoid opaque ROLLBACK.
-- =====================================================================

-- ---------- 1. member_balances ----------------------------------------

CREATE TABLE IF NOT EXISTS member_balances (
    id                   uuid           PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id            uuid           NOT NULL,
    total_claimed        numeric(19,4)  NOT NULL DEFAULT 0,
    total_approved       numeric(19,4)  NOT NULL DEFAULT 0,
    total_paid           numeric(19,4)  NOT NULL DEFAULT 0,
    outstanding_balance  numeric(19,4)  NOT NULL DEFAULT 0,
    currency_code        varchar(3)     NOT NULL,
    last_updated_at      timestamptz    NOT NULL DEFAULT now(),
    created_at           timestamptz    NOT NULL DEFAULT now(),
    CONSTRAINT uq_member_balances UNIQUE (member_id, currency_code)
);

CREATE INDEX IF NOT EXISTS idx_member_balances_outstanding
    ON member_balances(outstanding_balance DESC)
    WHERE outstanding_balance > 0;
CREATE INDEX IF NOT EXISTS idx_member_balances_member
    ON member_balances(member_id);

-- ---------- 2. payment_runs.payee_type --------------------------------

-- Default 'PROVIDER' for backfill of existing runs — historical runs were
-- provider-only by construction (V071 landed the MEMBER item support but
-- the generator only started enumerating members in this phase).
ALTER TABLE payment_runs
    ADD COLUMN IF NOT EXISTS payee_type varchar(10) NOT NULL DEFAULT 'PROVIDER'
        CHECK (payee_type IN ('PROVIDER', 'MEMBER'));

-- Enforce homogeneity via trigger — CHECK cannot cross-reference tables.
CREATE OR REPLACE FUNCTION assert_payment_run_item_payee_type_matches()
RETURNS trigger AS $$
DECLARE
    run_payee_type text;
BEGIN
    SELECT payee_type INTO run_payee_type
      FROM payment_runs
     WHERE id = NEW.payment_run_id;
    IF run_payee_type IS NULL THEN
        RAISE EXCEPTION 'parent payment_run % not found', NEW.payment_run_id;
    END IF;
    IF run_payee_type <> NEW.payee_type THEN
        RAISE EXCEPTION 'payment_run_item.payee_type % does not match parent run payee_type %',
            NEW.payee_type, run_payee_type;
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_payment_run_item_payee_type_match ON payment_run_items;
CREATE TRIGGER trg_payment_run_item_payee_type_match
    BEFORE INSERT OR UPDATE OF payee_type ON payment_run_items
    FOR EACH ROW EXECUTE FUNCTION assert_payment_run_item_payee_type_matches();

-- ---------- 3. member_payable_applications source_type extension -----

ALTER TABLE member_payable_applications
    DROP CONSTRAINT IF EXISTS member_payable_applications_source_type_check;
ALTER TABLE member_payable_applications
    ADD CONSTRAINT member_payable_applications_source_type_check
        CHECK (source_type IN ('CTC', 'PAYMENT'));

-- Idempotency guard: Kafka replays or retry loops must never double-apply
-- the same (source_type, source_id) tuple.
CREATE UNIQUE INDEX IF NOT EXISTS uq_mpa_source
    ON member_payable_applications(source_type, source_id);

-- ---------- 4. Backfill member_balances -------------------------------

-- Full backfill (grilling decision G8c) — one row per (member_id,
-- currency_code) with:
--   total_claimed  = SUM(claims.claimed_amount) where payee_type='MEMBER'
--   total_approved = SUM(member_payables.amount) where status IN ('open','applied')
--   total_paid     = SUM(member_payable_applications.amount_applied)
--                    (currently CTC only; PAYMENT rows land at Phase 3)
-- Idempotent via ON CONFLICT — safe to re-run.

INSERT INTO member_balances (member_id, currency_code, total_claimed, total_approved, total_paid, outstanding_balance)
SELECT
    m.member_id,
    m.currency_code,
    COALESCE(claimed.total, 0)  AS total_claimed,
    COALESCE(approved.total, 0) AS total_approved,
    COALESCE(paid.total, 0)     AS total_paid,
    COALESCE(approved.total, 0) - COALESCE(paid.total, 0) AS outstanding_balance
FROM (
    -- Unified set of (member, currency) touched by any source
    SELECT c.member_id, c.currency_code
      FROM claims c
     WHERE c.payee_type = 'MEMBER' AND c.member_id IS NOT NULL
     GROUP BY c.member_id, c.currency_code
    UNION
    SELECT mp.member_id, mp.currency_code
      FROM member_payables mp
     GROUP BY mp.member_id, mp.currency_code
) m
LEFT JOIN (
    SELECT c.member_id, c.currency_code, SUM(c.claimed_amount) AS total
      FROM claims c
     WHERE c.payee_type = 'MEMBER' AND c.member_id IS NOT NULL
     GROUP BY c.member_id, c.currency_code
) claimed ON claimed.member_id = m.member_id AND claimed.currency_code = m.currency_code
LEFT JOIN (
    SELECT mp.member_id, mp.currency_code, SUM(mp.amount) AS total
      FROM member_payables mp
     WHERE mp.status IN ('open', 'applied')
     GROUP BY mp.member_id, mp.currency_code
) approved ON approved.member_id = m.member_id AND approved.currency_code = m.currency_code
LEFT JOIN (
    SELECT mp.member_id, mp.currency_code, SUM(mpa.amount_applied) AS total
      FROM member_payable_applications mpa
      JOIN member_payables mp ON mp.id = mpa.member_payable_id
     GROUP BY mp.member_id, mp.currency_code
) paid ON paid.member_id = m.member_id AND paid.currency_code = m.currency_code
ON CONFLICT (member_id, currency_code) DO UPDATE
    SET total_claimed       = EXCLUDED.total_claimed,
        total_approved      = EXCLUDED.total_approved,
        total_paid          = EXCLUDED.total_paid,
        outstanding_balance = EXCLUDED.outstanding_balance,
        last_updated_at     = now();
