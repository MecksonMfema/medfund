-- Mirror of production V142 (actuarial Phase 5). The MemberService.recordDeath
-- pathway routes through the Phase-13 MemberStatusTransitionService with
-- newStatus='deceased' + reasonCode='member_death'; both need to be in the
-- IT baseline's CHECK vocabularies or the insert fires a 23514.
--
-- V001 declares members.status as an INLINE column CHECK — Postgres names it
-- automatically (typically {table}_{column}_check but not guaranteed), so
-- rather than hard-code the name we dynamically drop any CHECK on the
-- members.status column that carries the old vocabulary, then add a named
-- replacement that also covers 'deceased'. member_status_history.reason_code
-- has an explicit CONSTRAINT name so straight DROP + ADD works there.

DO $$
DECLARE
    cn text;
BEGIN
    FOR cn IN
        SELECT conname
          FROM pg_constraint
         WHERE conrelid = 'members'::regclass
           AND contype = 'c'
           AND pg_get_constraintdef(oid) ILIKE '%status%enrolled%'
    LOOP
        EXECUTE 'ALTER TABLE members DROP CONSTRAINT ' || quote_ident(cn);
    END LOOP;
END $$;

ALTER TABLE members
    ADD CONSTRAINT members_status_vocab CHECK (
        status IN ('enrolled','active','suspended','terminated','deactivated','lapsed','deceased')
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
