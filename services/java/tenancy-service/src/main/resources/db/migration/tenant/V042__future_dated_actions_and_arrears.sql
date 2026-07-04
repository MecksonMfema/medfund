-- =====================================================================
-- V042: Future-dated member/group status changes + arrears escalation
-- =====================================================================
-- Two related expansions that share the same schema shape:
--
-- 1. Every lifecycle action on a member or group can now carry a future
--    effective date. Instead of flipping status immediately, the service
--    stashes the target status + date in a "scheduled_status" trio and
--    a daily job rolls it forward when its day arrives. Same trio is
--    used for enrolment (enrol today, effective from next month).
--
-- 2. Arrears escalation formalises the deactivation branch: after
--    dunning_config.write_off_days of unpaid aged debt, the tenant can
--    auto-flip a suspended member/group to a new "deactivated" status
--    that stops billing (the candidate resolvers already exclude
--    anything outside 'active'/'suspended'). Rename write_off_days →
--    deactivation_days so the label matches the resulting status.
--
-- Idempotent throughout so re-runs (dev outages, sweep migrations, per-
-- tenant re-applies) are no-ops.

-- ── Step 1: scheduled status trio on members ──────────────────────
ALTER TABLE members
    ADD COLUMN IF NOT EXISTS scheduled_status              VARCHAR(20),
    ADD COLUMN IF NOT EXISTS scheduled_status_effective_from DATE,
    ADD COLUMN IF NOT EXISTS scheduled_status_reason       VARCHAR(64);

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'members_scheduled_status_pair') THEN
        ALTER TABLE members
            ADD CONSTRAINT members_scheduled_status_pair CHECK (
                (scheduled_status IS NULL AND scheduled_status_effective_from IS NULL)
             OR (scheduled_status IS NOT NULL AND scheduled_status_effective_from IS NOT NULL)
            );
    END IF;
END $$;

-- ── Step 2: same trio on groups ───────────────────────────────────
ALTER TABLE groups
    ADD COLUMN IF NOT EXISTS scheduled_status              VARCHAR(20),
    ADD COLUMN IF NOT EXISTS scheduled_status_effective_from DATE,
    ADD COLUMN IF NOT EXISTS scheduled_status_reason       VARCHAR(64);

DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'groups_scheduled_status_pair') THEN
        ALTER TABLE groups
            ADD CONSTRAINT groups_scheduled_status_pair CHECK (
                (scheduled_status IS NULL AND scheduled_status_effective_from IS NULL)
             OR (scheduled_status IS NOT NULL AND scheduled_status_effective_from IS NOT NULL)
            );
    END IF;
END $$;

-- ── Step 3: formalise status vocabulary via CHECK constraints ─────
-- Vocabulary was implicit until now (V001 seeded the column with no
-- CHECK). 'deactivated' is new (auto-arrears end state); 'enrolled'
-- is the pre-activation state (existing default in MemberService).
DO $$ BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'members_status_vocab') THEN
        ALTER TABLE members
            ADD CONSTRAINT members_status_vocab CHECK (
                status IN ('enrolled','active','suspended','terminated','deactivated')
            );
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'members_scheduled_status_vocab') THEN
        ALTER TABLE members
            ADD CONSTRAINT members_scheduled_status_vocab CHECK (
                scheduled_status IS NULL OR
                scheduled_status IN ('enrolled','active','suspended','terminated','deactivated')
            );
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'groups_status_vocab') THEN
        ALTER TABLE groups
            ADD CONSTRAINT groups_status_vocab CHECK (
                status IN ('active','suspended','terminated','deactivated')
            );
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'groups_scheduled_status_vocab') THEN
        ALTER TABLE groups
            ADD CONSTRAINT groups_scheduled_status_vocab CHECK (
                scheduled_status IS NULL OR
                scheduled_status IN ('active','suspended','terminated','deactivated')
            );
    END IF;
END $$;

-- ── Step 4: indexes for the daily scheduler ───────────────────────
-- The job's two queries hit these tables often: "any row due today?".
-- Partial indexes stay tiny because most rows have no schedule.
CREATE INDEX IF NOT EXISTS idx_members_scheduled_status_due
    ON members(scheduled_status_effective_from)
    WHERE scheduled_status_effective_from IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_groups_scheduled_status_due
    ON groups(scheduled_status_effective_from)
    WHERE scheduled_status_effective_from IS NOT NULL;
-- "Enrolled members whose day has come" — the daily roll's other query.
CREATE INDEX IF NOT EXISTS idx_members_enrolled_due
    ON members(enrollment_date)
    WHERE status = 'enrolled';

-- ── Step 5: dunning_config column rename ──────────────────────────
-- write_off_days is finance-speak; deactivation_days matches the
-- resulting status. Rename in place — Flyway records the schema
-- change, Java DTOs update in the same commit.
DO $$ BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.columns
                WHERE table_name = 'dunning_config' AND column_name = 'write_off_days') THEN
        ALTER TABLE dunning_config RENAME COLUMN write_off_days TO deactivation_days;
    END IF;
END $$;
