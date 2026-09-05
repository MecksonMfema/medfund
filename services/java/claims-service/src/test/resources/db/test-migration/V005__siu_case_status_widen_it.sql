-- Phase 19 §B Phase 8 test schema — widen siu_case.status to the full
-- 5-state machine + add the four-eyes staging columns.
--
-- Mirrors the prod tenant migration
--   V174__siu_case_status_widen.sql
-- for the flat `public`-schema IT world. Written as a *new* higher-
-- numbered migration rather than an edit of V003 per
-- feedback_never_edit_applied_migrations.

ALTER TABLE siu_case DROP CONSTRAINT IF EXISTS siu_case_status_chk;

ALTER TABLE siu_case
    ADD CONSTRAINT siu_case_status_chk CHECK (status IN (
        'OPEN','ASSIGNED','UNDER_REVIEW','PENDING_APPROVAL','REOPENED',
        'CLOSED_CONFIRMED_FRAUD','CLOSED_DISMISSED_FALSE_POSITIVE',
        'CLOSED_REFERRED_LAW_ENFORCEMENT','CLOSED_ACTION_TAKEN'));

ALTER TABLE siu_case
    ADD COLUMN IF NOT EXISTS proposed_by              UUID,
    ADD COLUMN IF NOT EXISTS proposed_by_email        VARCHAR(255),
    ADD COLUMN IF NOT EXISTS proposed_at              TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS proposed_outcome         VARCHAR(48),
    ADD COLUMN IF NOT EXISTS proposed_saved_amount    NUMERIC(19,4),
    ADD COLUMN IF NOT EXISTS proposed_saved_currency  VARCHAR(3),
    ADD COLUMN IF NOT EXISTS proposed_closure_reason  TEXT;

CREATE INDEX IF NOT EXISTS siu_case_pending_approval_idx
    ON siu_case (proposed_at)
    WHERE status = 'PENDING_APPROVAL';
