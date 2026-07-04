-- Direct owner columns on transactions. Prior model required a
-- transaction to reference a specific contribution or invoice — that
-- broke "pay ahead" flows because it left no way to record money owed
-- to a group before its next billing cycle existed. From now on a
-- transaction is anchored to its owner (a group or an individual
-- member) and MAY additionally reference a contribution/invoice when
-- the payment is meant for one specific line.
--
-- Backfill: infer owner from the existing contribution/invoice link
-- where possible so historical rows keep a coherent ledger. Rows with
-- neither link stay NULL (no anchor — dead entries, if any exist).

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS group_id  UUID REFERENCES groups(id),
    ADD COLUMN IF NOT EXISTS member_id UUID REFERENCES members(id);

-- Historical rows: derive owner from linked contribution then invoice.
UPDATE transactions t
   SET group_id  = COALESCE(t.group_id,  c.group_id),
       member_id = COALESCE(t.member_id, c.member_id)
  FROM contributions c
 WHERE t.contribution_id = c.id
   AND t.group_id IS NULL
   AND t.member_id IS NULL;

UPDATE transactions t
   SET group_id  = COALESCE(t.group_id,  i.group_id),
       member_id = COALESCE(t.member_id, i.member_id)
  FROM invoices i
 WHERE t.invoice_id = i.id
   AND t.group_id IS NULL
   AND t.member_id IS NULL;

-- Partial indexes so the balance ledger lookups (per-group + per-member)
-- scan the small subset of rows relevant to a given holder.
CREATE INDEX IF NOT EXISTS idx_transactions_group  ON transactions(group_id)  WHERE group_id  IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_transactions_member ON transactions(member_id) WHERE member_id IS NOT NULL;

-- Exactly-one-owner check: from V039 onwards a NEW transaction must
-- carry one of {group_id, member_id}. Legacy rows without either are
-- tolerated so the migration doesn't fail on any residue — the CHECK
-- treats NULL/NULL as valid so the historical shape survives.
ALTER TABLE transactions
    ADD CONSTRAINT transactions_owner_exclusive CHECK (
        group_id IS NULL OR member_id IS NULL
    );
