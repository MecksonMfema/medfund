-- =====================================================================
-- V041: Reason column + transaction_types reshape
-- =====================================================================
-- Consolidates several vocabulary fixes that were originally drafted
-- into V040 (retained as a no-op so its history row stays valid, see
-- feedback_never_edit_applied_migrations):
--
-- 1. transactions.reason column — free-text justification the operator
--    records for CREDIT / DEBIT adjustments.
--
-- 2. ORDINARY sign fix — ORDINARY was '+' which INCREASED payer
--    balance on a payment. Rename to PAYMENT and flip to '-'. Reverse
--    the ledger impact of any past ORDINARY row (subtract 2×amount so
--    the net effect is '-amount', matching the corrected sign).
--
-- 3. Clear vocabulary — self-describing codes with ≤ 30-char labels
--    so the picker and statement lines read cleanly:
--         PAYMENT              (-) Payment received
--         REFUND               (+) Refund issued
--         WRITE_OFF            (-) Write-off
--         PAYMENT_REVERSAL     (+) Payment reversal (chargeback)
--         CREDIT               (-) Credit adjustment (needs reason)
--         DEBIT                (+) Debit adjustment (needs reason)
--         LATE_ENROLMENT_CHARGE (+) Late enrolment charge
--         LATE_TERMINATION_CREDIT (-) Late termination credit
--         SCHEME_UPGRADE_ARREARS (+) Scheme upgrade arrears
--         SCHEME_DOWNGRADE_REBATE (-) Scheme downgrade rebate
--
-- Balance convention: balance = what the payer still owes us.
--   +  → increases balance (charges, debits, refunds handed out)
--   -  → decreases balance (payments received, credits, write-offs)
--
-- Every step is idempotent (IF NOT EXISTS / UPPER-matched updates that
-- run zero times on second execution / ON CONFLICT DO NOTHING) so
-- Flyway can safely rerun the migration if it ever needs to.

-- ── Step 0: schema — reason column ────────────────────────────────
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS reason TEXT;

-- ── Step 1: reverse the balance impact of past ORDINARY payments ───
-- Second run finds no rows with transaction_type='ORDINARY' (Step 2
-- renames them) so this UPDATE is a no-op on replay.
WITH bad_group_payments AS (
    SELECT group_id, currency_code, SUM(amount) AS total
      FROM transactions
     WHERE UPPER(transaction_type) = 'ORDINARY'
       AND group_id IS NOT NULL
       AND status = 'completed'
     GROUP BY group_id, currency_code
)
UPDATE group_running_balance g
   SET balance    = balance - 2 * bp.total,
       updated_at = NOW()
  FROM bad_group_payments bp
 WHERE g.group_id      = bp.group_id
   AND g.currency_code = bp.currency_code;

WITH bad_member_payments AS (
    SELECT member_id, currency_code, SUM(amount) AS total
      FROM transactions
     WHERE UPPER(transaction_type) = 'ORDINARY'
       AND member_id IS NOT NULL
       AND status = 'completed'
     GROUP BY member_id, currency_code
)
UPDATE member_running_balance m
   SET balance    = balance - 2 * bp.total,
       updated_at = NOW()
  FROM bad_member_payments bp
 WHERE m.member_id     = bp.member_id
   AND m.currency_code = bp.currency_code;

-- ── Step 2: rename historical snapshot labels on transactions ────
-- transaction_type is a text snapshot so a catalogue rename doesn't
-- orphan rows. Bring snapshots up to the new vocabulary in place.
UPDATE transactions SET transaction_type = 'PAYMENT'          WHERE UPPER(transaction_type) = 'ORDINARY';
UPDATE transactions SET transaction_type = 'DEBIT'            WHERE UPPER(transaction_type) = 'ADJUSTMENT';
UPDATE transactions SET transaction_type = 'PAYMENT_REVERSAL' WHERE UPPER(transaction_type) = 'REVERSAL';
-- CREDIT and DEBIT keep their codes; only the sign discipline changes.

-- ── Step 3: rewrite the catalogue ─────────────────────────────────
-- UPDATE existing rows in place so the FK on transactions.transaction_type_id
-- keeps resolving; INSERT any new codes; retire ADJUSTMENT.
UPDATE transaction_types
   SET code  = 'PAYMENT',
       label = 'Payment received',
       sign  = '-',
       requires_approval = FALSE
 WHERE code = 'ORDINARY';

UPDATE transaction_types
   SET label = 'Debit adjustment',
       sign  = '+'
 WHERE code = 'DEBIT';

UPDATE transaction_types
   SET label = 'Credit adjustment',
       sign  = '-'
 WHERE code = 'CREDIT';

UPDATE transaction_types
   SET code  = 'PAYMENT_REVERSAL',
       label = 'Payment reversal',
       sign  = '+'
 WHERE code = 'REVERSAL';

-- Retire the plain ADJUSTMENT code — DEBIT / CREDIT do this job now.
-- Historical rows already remapped to DEBIT in Step 2.
DELETE FROM transaction_types WHERE code = 'ADJUSTMENT';

INSERT INTO transaction_types (code, label, sign, requires_approval) VALUES
    ('REFUND',                  'Refund issued',           '+', TRUE),
    ('WRITE_OFF',               'Write-off',               '-', TRUE),
    -- Fixed-cause adjustments — the type IS the explanation, so no
    -- operator-supplied reason is required. Sign is baked into the
    -- code so a picker never wonders whether "late enrolment" is up
    -- or down. Labels kept ≤ 30 chars for clean ledger / statement
    -- rendering per the operator-feedback thread from 2026-07-04.
    ('LATE_ENROLMENT_CHARGE',   'Late enrolment charge',   '+', FALSE),
    ('LATE_TERMINATION_CREDIT', 'Late termination credit', '-', FALSE),
    ('SCHEME_UPGRADE_ARREARS',  'Scheme upgrade arrears',  '+', FALSE),
    ('SCHEME_DOWNGRADE_REBATE', 'Scheme downgrade rebate', '-', FALSE)
ON CONFLICT (code) DO NOTHING;
