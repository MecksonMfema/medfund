-- =====================================================================
-- V034: One contribution per (person, scheme, period) — DB-level guard
-- =====================================================================
-- The application-side check in BillingService.commitBilling stops
-- the wizard from committing the same period twice. This adds the
-- DB-level UNIQUE index so any other path (manual generate-billing,
-- a background cron, a future bulk-import) is held to the same rule.
--
-- "Person" splits in two:
--   member rows    → dependant_id IS NULL
--   dependant rows → dependant_id IS NOT NULL
-- Partial unique indexes give each its own constraint without
-- conflicting NULLs (Postgres treats NULL ≠ NULL for uniqueness,
-- so a plain UNIQUE on (..., dependant_id, ...) would let unlimited
-- duplicate member rows slip through).
--
-- The DELETE step below removes legacy duplicates that existed
-- before this guard landed (4 rows on the dev tenant as of writing).
-- Strategy: keep the earliest created_at per group; delete the rest.
-- =====================================================================

-- ── 1. Deduplicate ──────────────────────────────────────────────────
WITH ranked AS (
    SELECT id,
           ROW_NUMBER() OVER (
               PARTITION BY member_id,
                            COALESCE(dependant_id, '00000000-0000-0000-0000-000000000000'::uuid),
                            scheme_id, period_start, period_end
               ORDER BY created_at, id
           ) AS rn
      FROM contributions
)
DELETE FROM contributions
 WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

-- ── 2. Unique partial indexes ───────────────────────────────────────
-- Member rows: one contribution per (member, scheme, period).
CREATE UNIQUE INDEX IF NOT EXISTS ux_contributions_member_period
    ON contributions (member_id, scheme_id, period_start, period_end)
    WHERE dependant_id IS NULL;

-- Dependant rows: one contribution per (dependant, scheme, period).
-- member_id is included so a corrupted dependant→member re-pointing
-- can't silently merge two dependants into one bucket.
CREATE UNIQUE INDEX IF NOT EXISTS ux_contributions_dependant_period
    ON contributions (dependant_id, member_id, scheme_id, period_start, period_end)
    WHERE dependant_id IS NOT NULL;
