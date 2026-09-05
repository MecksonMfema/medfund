-- Phase 19 §B Phase 8 — widen siu_case.status to the full 5-state machine.
--
-- MVP had:  OPEN, UNDER_REVIEW,
--           CLOSED_CONFIRMED_FRAUD, CLOSED_DISMISSED_FALSE_POSITIVE.
-- §B adds:  ASSIGNED, PENDING_APPROVAL, REOPENED (transient),
--           CLOSED_REFERRED_LAW_ENFORCEMENT, CLOSED_ACTION_TAKEN.
--
-- Also adds the four-eyes staging columns used by proposeClosure /
-- approveClosure so investigator ≠ supervisor per FR6.
--
-- Rule-2 guard: tenant-schema table — queries must NOT use `public.`
-- prefix per bug_public_prefix_silent_rollback.

ALTER TABLE siu_case DROP CONSTRAINT IF EXISTS siu_case_status_chk;

ALTER TABLE siu_case
    ADD CONSTRAINT siu_case_status_chk CHECK (status IN (
        'OPEN','ASSIGNED','UNDER_REVIEW','PENDING_APPROVAL','REOPENED',
        'CLOSED_CONFIRMED_FRAUD','CLOSED_DISMISSED_FALSE_POSITIVE',
        'CLOSED_REFERRED_LAW_ENFORCEMENT','CLOSED_ACTION_TAKEN'));

-- Four-eyes staging columns for pending closures. Populated by
-- proposeClosure (UNDER_REVIEW → PENDING_APPROVAL); consumed and
-- copied onto the terminal closed_* fields by approveClosure.
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

COMMENT ON COLUMN siu_case.proposed_by IS
    'Investigator who proposed a non-dismissal closure; supervisor must be a different actor to approve (four-eyes per FR6).';
COMMENT ON COLUMN siu_case.proposed_outcome IS
    'One of CONFIRMED_FRAUD, REFERRED_LAW_ENFORCEMENT, ACTION_TAKEN — mapped to the terminal CLOSED_* status on approveClosure.';
