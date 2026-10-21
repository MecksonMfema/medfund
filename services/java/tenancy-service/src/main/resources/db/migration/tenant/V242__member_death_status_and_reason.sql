-- ── Actuarial Phase 14 §D / Phase 5 (MemberService.recordDeath) ──────
-- V140 added members.death_date + members.cause_of_death. Phase 5 additionally
-- routes death-recording through the Phase-13 MemberStatusTransitionService
-- with newStatus='deceased' and reasonCode='member_death' so the death shows
-- up in the same audit trail as every other status flip. That requires two
-- CHECK vocabularies to be widened — done here because editing V101 or V112
-- in place would violate feedback_never_edit_applied_migrations.
--
-- Idempotent per the same pattern V101 uses (DROP + re-ADD). No data
-- backfill: 'deceased' is a forward-only status that only lands via
-- recordDeath, and no historical 'member_death' rows exist.

ALTER TABLE members
    DROP CONSTRAINT IF EXISTS members_status_vocab;
ALTER TABLE members
    ADD CONSTRAINT members_status_vocab CHECK (
        status IN ('enrolled','active','suspended','terminated','deactivated','lapsed','deceased')
    );

ALTER TABLE members
    DROP CONSTRAINT IF EXISTS members_scheduled_status_vocab;
ALTER TABLE members
    ADD CONSTRAINT members_scheduled_status_vocab CHECK (
        scheduled_status IS NULL OR
        scheduled_status IN ('enrolled','active','suspended','terminated','deactivated','lapsed','deceased')
    );

ALTER TABLE member_status_history
    DROP CONSTRAINT IF EXISTS chk_member_status_history_reason;
ALTER TABLE member_status_history
    ADD CONSTRAINT chk_member_status_history_reason CHECK (reason_code IS NULL OR reason_code IN (
        'initial_backfill', 'backfill_from_termination_date', 'backfill_from_suspend_reason',
        'admin_activate', 'admin_suspend', 'admin_terminate', 'admin_deactivate', 'admin_lapse',
        'arrears_lapse', 'arrears_clear', 'scheduled_change', 'group_cascade',
        'dependant_swap', 'enrolment', 'auto_termination', 'member_death', 'other'
    ));
