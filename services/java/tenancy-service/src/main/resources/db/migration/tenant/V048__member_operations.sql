-- =====================================================================
-- V048: Mid-life member operations (group change, dependant/member swap)
-- =====================================================================
-- Adds three workflow tables + two soft-swap pointers + three new
-- transaction_type codes to underpin the "member operations that
-- affect billing" feature set:
--
--   1. pending_group_changes — request/approve/apply state machine for
--      a member moving between groups. Mirrors the scheme_changes
--      shape so both operations share an idiom. Back-dated requests
--      are applied immediately (no PENDING row) and the arrears/
--      rebate is posted asynchronously by GroupChangedConsumer via
--      LateAdjustmentService.postFixedAggregate.
--
--   2. member_dependant_swaps — role-swap between a principal and one
--      of their dependants. On apply the dependant is promoted into
--      members and the old principal is demoted into dependants;
--      historical FKs on claims/contributions are NOT rewritten,
--      instead a `swapped_to_id` column on members and dependants
--      lets reporting queries follow the redirect.
--
--   3. scheme_changes.change_kind — classifies each request as UPGRADE
--      / DOWNGRADE / CURRENCY_CHANGE / CROSS_GRADE. Existing rows
--      backfill to CROSS_GRADE (safe default — no adjustment
--      classification implied). SchemeChangedConsumer routes the
--      currency-change variant to CURRENCY_CHANGE_ADJUSTMENT with an
--      FX-aware delta.
--
--   4. transaction_types seed — GROUP_CHANGE_ARREARS,
--      GROUP_CHANGE_REBATE, CURRENCY_CHANGE_ADJUSTMENT. Sign discipline
--      follows V041: '+' increases the payer's balance, '-' decreases.
--
-- Every step is idempotent (IF NOT EXISTS / ON CONFLICT DO NOTHING) so
-- Flyway can safely rerun the migration if it ever needs to
-- (feedback_never_edit_applied_migrations).

-- ── Table 1: pending_group_changes ───────────────────────────────────
CREATE TABLE IF NOT EXISTS pending_group_changes (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id          UUID         NOT NULL REFERENCES members(id),
    from_group_id      UUID         REFERENCES groups(id),
    to_group_id        UUID         NOT NULL REFERENCES groups(id),
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','APPROVED','APPLIED','REJECTED','CANCELLED')),
    requested_date     DATE         NOT NULL DEFAULT CURRENT_DATE,
    effective_date     DATE         NOT NULL
        CHECK (extract(day from effective_date) = 1),
    reason             TEXT,
    rejection_reason   TEXT,
    approved_by        UUID,
    approved_at        TIMESTAMPTZ,
    applied_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         UUID
);

CREATE INDEX IF NOT EXISTS idx_pending_group_changes_member
    ON pending_group_changes(member_id);
CREATE INDEX IF NOT EXISTS idx_pending_group_changes_status
    ON pending_group_changes(status);
-- Only one live request per member. APPLIED / REJECTED / CANCELLED
-- rows are excluded so history piles up cleanly.
CREATE UNIQUE INDEX IF NOT EXISTS ux_pending_group_changes_live_per_member
    ON pending_group_changes(member_id)
    WHERE status IN ('PENDING','APPROVED');
-- Fast lookup for ScheduledChangesExecutor daily sweep.
CREATE INDEX IF NOT EXISTS idx_pending_group_changes_ready
    ON pending_group_changes(effective_date)
    WHERE status = 'APPROVED';

-- ── Table 2: member_dependant_swaps ──────────────────────────────────
CREATE TABLE IF NOT EXISTS member_dependant_swaps (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    old_member_id      UUID         NOT NULL REFERENCES members(id),
    dependant_id       UUID         NOT NULL REFERENCES dependants(id),
    -- Populated on apply: the new members row promoted from the dependant
    new_member_id      UUID         REFERENCES members(id),
    -- Populated on apply: the new dependants row that hosts the demoted
    -- old principal.
    old_dependant_id   UUID         REFERENCES dependants(id),
    status             VARCHAR(20)  NOT NULL DEFAULT 'PENDING'
        CHECK (status IN ('PENDING','APPROVED','APPLIED','REJECTED','CANCELLED')),
    requested_date     DATE         NOT NULL DEFAULT CURRENT_DATE,
    effective_date     DATE         NOT NULL
        CHECK (extract(day from effective_date) = 1),
    reason             TEXT,
    rejection_reason   TEXT,
    approved_by        UUID,
    approved_at        TIMESTAMPTZ,
    applied_at         TIMESTAMPTZ,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    created_by         UUID
);

CREATE INDEX IF NOT EXISTS idx_member_dependant_swaps_old_member
    ON member_dependant_swaps(old_member_id);
CREATE INDEX IF NOT EXISTS idx_member_dependant_swaps_dependant
    ON member_dependant_swaps(dependant_id);
CREATE INDEX IF NOT EXISTS idx_member_dependant_swaps_status
    ON member_dependant_swaps(status);
-- Only one live swap per (member, dependant) pair.
CREATE UNIQUE INDEX IF NOT EXISTS ux_member_dependant_swaps_live_pair
    ON member_dependant_swaps(old_member_id, dependant_id)
    WHERE status IN ('PENDING','APPROVED');
CREATE INDEX IF NOT EXISTS idx_member_dependant_swaps_ready
    ON member_dependant_swaps(effective_date)
    WHERE status = 'APPROVED';

-- ── Column: scheme_changes.change_kind ───────────────────────────────
ALTER TABLE scheme_changes
    ADD COLUMN IF NOT EXISTS change_kind VARCHAR(20) NOT NULL DEFAULT 'CROSS_GRADE'
        CHECK (change_kind IN ('UPGRADE','DOWNGRADE','CURRENCY_CHANGE','CROSS_GRADE'));

COMMENT ON COLUMN scheme_changes.change_kind IS
    'Classification of the scheme change for adjustment routing. UPGRADE → SCHEME_UPGRADE_ARREARS, DOWNGRADE → SCHEME_DOWNGRADE_REBATE, CURRENCY_CHANGE → CURRENCY_CHANGE_ADJUSTMENT (FX-aware), CROSS_GRADE → no automatic ledger post. Existing rows backfill to CROSS_GRADE to avoid retroactive charges.';

-- ── Columns: members.swapped_to_id + dependants.swapped_to_id ────────
ALTER TABLE members
    ADD COLUMN IF NOT EXISTS swapped_to_id UUID REFERENCES dependants(id);

ALTER TABLE dependants
    ADD COLUMN IF NOT EXISTS swapped_to_id UUID REFERENCES members(id);

COMMENT ON COLUMN members.swapped_to_id IS
    'When a member is demoted via member_dependant_swaps, this points at the dependants row that now hosts them. Historical claims/contributions FKs continue to point at this members row; reporting queries follow this redirect. NULL for members who have never been swapped.';

COMMENT ON COLUMN dependants.swapped_to_id IS
    'When a dependant is promoted via member_dependant_swaps, this points at the members row that now hosts them. Complement of members.swapped_to_id. NULL for dependants who have never been swapped.';

-- ── Seed: three new transaction_type codes ───────────────────────────
INSERT INTO transaction_types (code, label, sign, requires_approval) VALUES
    ('GROUP_CHANGE_ARREARS',       'Group change arrears',        '+', FALSE),
    ('GROUP_CHANGE_REBATE',        'Group change rebate',         '-', FALSE),
    ('CURRENCY_CHANGE_ADJUSTMENT', 'Currency change adjustment',  '+', FALSE)
ON CONFLICT (code) DO NOTHING;
