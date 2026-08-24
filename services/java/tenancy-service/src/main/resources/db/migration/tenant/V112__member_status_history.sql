-- ── Phase 13 §A per L2: per-member status-transition audit trail ─────
-- Written by MemberStatusTransitionService via the MemberService.transitionStatus
-- retrofit (§A Phase 2). Retro-fills from termination_date + suspend_reason.
--
-- reason_code vocab covers every existing write path (arrears consumers,
-- scheduled executor, group cascade, enrolment, dependant swap, rules-engine
-- auto-term) plus the backfill arms.

CREATE TABLE IF NOT EXISTS member_status_history (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id        UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    from_status      VARCHAR(30),
    to_status        VARCHAR(30) NOT NULL,
    effective_at     TIMESTAMPTZ NOT NULL,
    actor_id         UUID,
    actor_email      VARCHAR(255),
    reason_code      VARCHAR(50),
    reason_note      TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_member_status_history_reason CHECK (reason_code IS NULL OR reason_code IN (
        'initial_backfill', 'backfill_from_termination_date', 'backfill_from_suspend_reason',
        'admin_activate', 'admin_suspend', 'admin_terminate', 'admin_deactivate', 'admin_lapse',
        'arrears_lapse', 'arrears_clear', 'scheduled_change', 'group_cascade',
        'dependant_swap', 'enrolment', 'auto_termination', 'other'
    ))
);

CREATE INDEX IF NOT EXISTS ix_member_status_history_member_effective
    ON member_status_history (member_id, effective_at DESC);

CREATE INDEX IF NOT EXISTS ix_member_status_history_status_effective
    ON member_status_history (to_status, effective_at DESC);

COMMENT ON TABLE member_status_history IS
    'Phase 13 §A per L2: per-member status-transition audit trail. Written through
     MemberStatusTransitionService (routed via MemberService.transitionStatus).';

-- Backfill per L7: seed row for every member at (from=NULL, to=<current status>, effective_at=created_at).
INSERT INTO member_status_history (member_id, from_status, to_status, effective_at, actor_email, reason_code)
SELECT id, NULL, status, created_at, 'migration', 'initial_backfill'
FROM members;

-- Second row for terminated members: (from='active', to='terminated', effective_at=termination_date).
-- termination_date is a DATE — midnight UTC marks the transition moment the schema retained.
INSERT INTO member_status_history (member_id, from_status, to_status, effective_at, actor_email, reason_code)
SELECT id, 'active', 'terminated',
       (termination_date::timestamp AT TIME ZONE 'UTC'),
       'migration', 'backfill_from_termination_date'
FROM members
WHERE termination_date IS NOT NULL AND status = 'terminated';

-- Second row for suspended-with-reason members: (from='active', to='suspended', effective_at=updated_at).
-- Uses updated_at as the best available approximation since suspend_at is not stored separately.
INSERT INTO member_status_history (member_id, from_status, to_status, effective_at, actor_email, reason_code, reason_note)
SELECT id, 'active', 'suspended', updated_at, 'migration', 'backfill_from_suspend_reason', suspend_reason
FROM members
WHERE suspend_reason IS NOT NULL AND status = 'suspended';
