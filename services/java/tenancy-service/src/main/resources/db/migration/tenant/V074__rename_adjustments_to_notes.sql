-- =====================================================================
-- V074: Rename `adjustments` → `notes` with a `direction` axis, and fold
-- the memo-only V016 `debit_notes`/`credit_notes` tables into it as
-- `note_type='MEMO'`. Extends `payment_advice_lines` with two new line
-- types (`NOTE_DEBIT`, `NOTE_CREDIT`) and a `back_period_run_id` column
-- so late-arriving notes can be traced to the run whose advice would
-- have carried them.
--
-- See thoughts/shared/plans/2026-08-10-adjustment-to-note-rename-with-advice-integration.md.
-- Sixteen decision forks (G1..G16) are pre-settled in the research doc's
-- `## Grilling decisions` section.
--
-- All work happens in one transaction so partial failure rolls back
-- cleanly — the plan's Migration Notes are explicit that this must NOT
-- be split into multiple V-numbers.
-- =====================================================================

-- ── 1. New columns on adjustments (still under the old name) ─────────
ALTER TABLE adjustments
    ADD COLUMN IF NOT EXISTS direction        VARCHAR(6),
    ADD COLUMN IF NOT EXISTS posted_at        TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS reverses_note_id UUID,
    ADD COLUMN IF NOT EXISTS type             VARCHAR(10) DEFAULT 'ORIGINAL' NOT NULL,
    ADD COLUMN IF NOT EXISTS note_type        VARCHAR(40);

-- ── 2. Backfill direction from the old adjustment_type (G1) ──────────
UPDATE adjustments SET direction = CASE adjustment_type
    WHEN 'IN_PAYMENT'    THEN 'CREDIT'
    WHEN 'NON_CASH_IN'   THEN 'CREDIT'
    WHEN 'PAYOUT'        THEN 'DEBIT'
    WHEN 'NON_CASH_OUT'  THEN 'DEBIT'
    WHEN 'TAX_WITHHELD'  THEN 'DEBIT'
END
WHERE direction IS NULL;

-- G9a: only TAX_WITHHELD has production callers today; any surviving
-- row without a note_type mapping fails the NOT NULL below, which is
-- the correct behaviour.
UPDATE adjustments SET note_type = adjustment_type
 WHERE adjustment_type = 'TAX_WITHHELD'
   AND note_type IS NULL;

-- Backfill posted_at from approved_at (fallback created_at) for
-- existing applied rows.
UPDATE adjustments
   SET posted_at = COALESCE(approved_at, created_at)
 WHERE status = 'applied'
   AND posted_at IS NULL;

-- G3 status remap: legacy 'cancelled' becomes 'reversed'.
UPDATE adjustments SET status = 'reversed' WHERE status = 'cancelled';

-- ── 3. Drop every CHECK constraint on adjustments ────────────────────
-- V016 declared the constraints inline (no name), so Postgres assigned
-- auto-names like `adjustments_status_check`, `adjustments_adjustment_type_check`,
-- and `adjustments_check` (table-level). Rather than guess the names,
-- iterate pg_constraint. The status/type/payee-or-member checks are all
-- being replaced with their `notes_*` equivalents below.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_class t ON c.conrelid = t.oid
          JOIN pg_namespace n ON t.relnamespace = n.oid
         WHERE t.relname = 'adjustments'
           AND n.nspname = current_schema()
           AND c.contype = 'c'
    LOOP
        EXECUTE 'ALTER TABLE adjustments DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
END $$;

-- Now safe to drop the old type column.
ALTER TABLE adjustments DROP COLUMN adjustment_type;

-- ── 4. Enforce NOT NULL on the new domain columns ────────────────────
ALTER TABLE adjustments ALTER COLUMN direction SET NOT NULL;
ALTER TABLE adjustments ALTER COLUMN note_type SET NOT NULL;

-- ── 5. Re-add the CHECK constraints under `notes_*` names ────────────
ALTER TABLE adjustments ADD CONSTRAINT notes_status_check
    CHECK (status IN ('pending','approved','applied','reversed'));

ALTER TABLE adjustments ADD CONSTRAINT notes_type_check
    CHECK (type IN ('ORIGINAL','REVERSAL'));

-- G3 compensating-entry CHECK.
ALTER TABLE adjustments ADD CONSTRAINT notes_reversal_check
    CHECK (
        (reverses_note_id IS NULL AND type = 'ORIGINAL')
        OR (reverses_note_id IS NOT NULL AND type = 'REVERSAL')
    );

-- G9 note_type domain.
ALTER TABLE adjustments ADD CONSTRAINT notes_type_domain_check
    CHECK (note_type IN ('TAX_WITHHELD','WRITE_OFF','GOODWILL','ENDORSEMENT_PREMIUM',
                         'PREMIUM_REFUND','PROVIDER_OVERPAYMENT_RECOVERY','MEMO'));

ALTER TABLE adjustments ADD CONSTRAINT notes_direction_check
    CHECK (direction IN ('DEBIT','CREDIT'));

-- G4 relax payee-required CHECK to allow MEMO.
ALTER TABLE adjustments ADD CONSTRAINT notes_payee_or_memo_check
    CHECK (provider_id IS NOT NULL OR member_id IS NOT NULL OR note_type = 'MEMO');

-- ── 6. Rename adjustment_number → note_number and reprefix (G16) ─────
ALTER TABLE adjustments RENAME COLUMN adjustment_number TO note_number;
UPDATE adjustments SET note_number = CASE
    WHEN note_type = 'MEMO'   THEN 'MEMO-' || SUBSTRING(note_number FROM 5)
    WHEN direction = 'DEBIT'  THEN 'DN-'   || SUBSTRING(note_number FROM 5)
    WHEN direction = 'CREDIT' THEN 'CN-'   || SUBSTRING(note_number FROM 5)
END
WHERE note_number LIKE 'ADJ-%';

-- ── 7. Rename table + indexes ────────────────────────────────────────
ALTER TABLE adjustments RENAME TO notes;
ALTER INDEX IF EXISTS idx_adjustments_provider RENAME TO idx_notes_provider;
ALTER INDEX IF EXISTS idx_adjustments_member   RENAME TO idx_notes_member;
ALTER INDEX IF EXISTS idx_adjustments_status   RENAME TO idx_notes_status;
DROP INDEX IF EXISTS idx_adjustments_type;

CREATE INDEX IF NOT EXISTS idx_notes_type       ON notes(note_type);
CREATE INDEX IF NOT EXISTS idx_notes_posted_at  ON notes(posted_at)       WHERE status = 'applied';
CREATE INDEX IF NOT EXISTS idx_notes_reverses   ON notes(reverses_note_id) WHERE reverses_note_id IS NOT NULL;

-- ── 8. Migrate V016 memo tables into notes as note_type='MEMO' (G4) ──
-- Direction: debit_notes → 'DEBIT', credit_notes → 'CREDIT'. Payee
-- columns stay NULL — MEMO notes are payee-less and won't render on
-- any advice.
INSERT INTO notes (id, note_number, amount, currency_code, reason, status,
                   created_at, updated_at, created_by,
                   direction, type, note_type, posted_at)
SELECT id,
       'MEMO-' || LPAD((100000 + (row_number() OVER (ORDER BY created_at))::int)::text, 6, '0'),
       amount, currency_code, notes, 'applied',
       created_at, created_at, created_by,
       'DEBIT', 'ORIGINAL', 'MEMO', created_at
  FROM debit_notes;

INSERT INTO notes (id, note_number, amount, currency_code, reason, status,
                   created_at, updated_at, created_by,
                   direction, type, note_type, posted_at)
SELECT id,
       'MEMO-' || LPAD((200000 + (row_number() OVER (ORDER BY created_at))::int)::text, 6, '0'),
       amount, currency_code, notes, 'applied',
       created_at, created_at, created_by,
       'CREDIT', 'ORIGINAL', 'MEMO', created_at
  FROM credit_notes;

DROP TABLE debit_notes;
DROP TABLE credit_notes;

-- ── 9. Extend payment_advice_lines with NOTE_DEBIT/NOTE_CREDIT ───────
-- and add back_period_run_id for late-arriving notes (G2).
-- V071 declared the line_type CHECK inline as
--   `line_type VARCHAR(24) NOT NULL CHECK (line_type IN (...))`,
-- so Postgres named it `payment_advice_lines_line_type_check`. Do a
-- defensive DO-block drop in case that name ever drifts.
DO $$
DECLARE
    r RECORD;
BEGIN
    FOR r IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_class t ON c.conrelid = t.oid
          JOIN pg_namespace n ON t.relnamespace = n.oid
         WHERE t.relname = 'payment_advice_lines'
           AND n.nspname = current_schema()
           AND c.contype = 'c'
           AND pg_get_constraintdef(c.oid) LIKE '%line_type%'
    LOOP
        EXECUTE 'ALTER TABLE payment_advice_lines DROP CONSTRAINT ' || quote_ident(r.conname);
    END LOOP;
END $$;

ALTER TABLE payment_advice_lines ADD CONSTRAINT payment_advice_lines_line_type_check
    CHECK (line_type IN ('CARRY_FORWARD','CLAIM_PAID','CTC_APPLIED','ADVANCE_APPLIED',
                         'TAX_WITHHELD','SHORTFALL','NOTE_DEBIT','NOTE_CREDIT'));

ALTER TABLE payment_advice_lines
    ADD COLUMN IF NOT EXISTS back_period_run_id UUID;

CREATE INDEX IF NOT EXISTS idx_pal_back_period_run
    ON payment_advice_lines(back_period_run_id)
    WHERE back_period_run_id IS NOT NULL;

-- reference_type on payment_advice_lines has no CHECK today (see V071:108),
-- so no DDL is needed to permit reference_type='note'. Documentation-only.
