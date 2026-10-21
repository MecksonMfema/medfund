-- ── Phase 11 §B / P7: widen member status vocabulary to include 'lapsed' ──
-- The auto-lapse chain terminates in a distinct 'lapsed' status so
-- MEMBER_STATUS_CHANGED events + downstream commission-clawback consumers
-- can distinguish operator-driven suspension/termination from
-- arrears-driven auto-lapse.
--
-- Idempotent: drop then re-add the two CHECK constraints. Preserves the
-- V042 vocabulary (enrolled/active/suspended/terminated/deactivated) and
-- adds 'lapsed' on top. Groups vocabulary intentionally NOT widened —
-- auto-lapse is member-scoped in MVP.
ALTER TABLE members
    DROP CONSTRAINT IF EXISTS members_status_vocab;
ALTER TABLE members
    ADD CONSTRAINT members_status_vocab CHECK (
        status IN ('enrolled','active','suspended','terminated','deactivated','lapsed')
    );

ALTER TABLE members
    DROP CONSTRAINT IF EXISTS members_scheduled_status_vocab;
ALTER TABLE members
    ADD CONSTRAINT members_scheduled_status_vocab CHECK (
        scheduled_status IS NULL OR
        scheduled_status IN ('enrolled','active','suspended','terminated','deactivated','lapsed')
    );
