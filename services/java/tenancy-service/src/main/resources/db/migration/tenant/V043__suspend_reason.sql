-- =====================================================================
-- V043: Persist "why is this suspended" on members and groups
-- =====================================================================
-- The scheduled_status_reason column (V042) only carries the reason
-- for a FUTURE-dated action. Immediate actions (which is what the
-- arrears escalator does, and what most operators do) flow through
-- MemberService.transitionStatus / GroupService.applyOrScheduleGroup
-- and don't stash the reason anywhere on the row — the audit trail is
-- the only witness.
--
-- Without a queryable "why", the arrears executor can't ask
-- "which rows are suspended because of arrears, not by operator?"
-- to run the auto-reactivate branch. This column closes that gap.
--
-- Semantics:
--   * Populated on every non-active status transition (suspend,
--     terminate, deactivate). The service writes the caller's reason
--     string.
--   * Cleared when the row transitions BACK to 'active'.
--   * NULL means "no active status change reason on record" — the
--     row is either in 'active' or 'enrolled', or the reason
--     predates V043 (harmless legacy).

ALTER TABLE members ADD COLUMN IF NOT EXISTS suspend_reason VARCHAR(64);
ALTER TABLE groups  ADD COLUMN IF NOT EXISTS suspend_reason VARCHAR(64);

-- Index the "arrears-suspended member/group" access pattern used by the
-- ArrearsEscalationExecutor's second sweep. Partial + tiny — most rows
-- have no reason.
CREATE INDEX IF NOT EXISTS idx_members_suspend_reason
    ON members(suspend_reason)
    WHERE suspend_reason IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_groups_suspend_reason
    ON groups(suspend_reason)
    WHERE suspend_reason IS NOT NULL;
