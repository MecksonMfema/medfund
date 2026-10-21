-- =====================================================================
-- V079: Rename COPAYMENT → COPAYMENT_RECEIPT + seed
--       finance:view_member_liabilities on view_creditors roles
-- =====================================================================
-- Phase 4 of the copayments standard flow (G13). The legacy 'COPAYMENT'
-- transaction-type has always been a member-side receipt — money the
-- member paid the fund against their share of a claim. The Phase 4
-- rename disambiguates that receipt path from the *liability* row
-- introduced by V078, and repositions the Angular surface as
-- "Cost-share receipts" (money in) vs. "Member Liabilities" (money owed).
--
-- Idempotent — the UPDATE only fires against rows that still carry the
-- old code; a tenant that manually renamed via the UI is unaffected.
--
-- Follow-up F2 sweeps any dashboards / exports that literal-match
-- 'COPAYMENT' outside the migration path.

-- 1. Catalogue rename. Label reads well in the /tenant/finance/copayments
--    picker; description clarifies purpose so tenant admins don't confuse
--    it with the liability row.
UPDATE transaction_types
   SET code  = 'COPAYMENT_RECEIPT',
       label = 'Cost-share receipt'
 WHERE code = 'COPAYMENT';

-- 2. Rewrite in-flight transactions referencing the old code. The FK on
--    transactions.transaction_type_id already points at the UPDATE'd row,
--    but a string transaction_type column is also kept in-sync for
--    reporting (V008 shape).
UPDATE transactions
   SET transaction_type = 'COPAYMENT_RECEIPT'
 WHERE transaction_type = 'COPAYMENT';

-- 3. New permission grant — seed finance:view_member_liabilities onto
--    every role that already holds finance:view_creditors. Same audience,
--    same trust-level: the roles that view provider/member owings should
--    also see the new member-liability ledger.
INSERT INTO role_permissions (id, role_id, permission, access_level)
SELECT gen_random_uuid(), rp.role_id, 'finance:view_member_liabilities', rp.access_level
  FROM role_permissions rp
 WHERE rp.permission = 'finance:view_creditors'
ON CONFLICT (role_id, permission) DO NOTHING;
