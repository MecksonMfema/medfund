-- ── Phase 13 §C Phase 7 per L16 + grill note 4 ──────────────────────
-- member_contribution_presence: monthly Contribution-presence per member.
-- PersistencyCohortReportService's HEALTH branch reads this once per
-- checkpoint cell — an O(1) index seek instead of a per-report 6-30s
-- runtime scan over contributions.
--
-- Refresh is chained after member_first_contribution refresh in
-- EarningScheduleClosureService.closeExpiredPeriodsForTenant so it rides
-- the same nightly pass. Freshness is tracked via a new
-- earning_schedule_run.contrib_presence_refresh_at column below.
--
-- Best-effort per matching refreshMemberFirstContribution pattern: a
-- REFRESH failure logs a warning but does not fail the executor.

CREATE MATERIALIZED VIEW IF NOT EXISTS member_contribution_presence AS
SELECT DISTINCT
    member_id,
    DATE_TRUNC('month', period_start)::DATE AS contribution_month
FROM contributions
WHERE member_id  IS NOT NULL
  AND period_start IS NOT NULL;

-- UNIQUE index guarantees the plan's "O(1) index seek per checkpoint cell"
-- assumption and lets a CONCURRENTLY refresh be considered later.
CREATE UNIQUE INDEX IF NOT EXISTS ux_member_contribution_presence
    ON member_contribution_presence (member_id, contribution_month);

COMMENT ON MATERIALIZED VIEW member_contribution_presence IS
    'Phase 13 §C per L16 + grill note 4: monthly Contribution-presence per member. '
    'Refreshed nightly by EarningScheduleClosureService.refreshMemberContributionPresence '
    'chained after member_first_contribution. Enables sub-ms checkpoint-cell lookup '
    'for PersistencyCohortReportService HEALTH branch.';

-- ── Freshness monitoring (grill note 4 resolution) ───────────────────
-- The report service checks the newest run row's contrib_presence_refresh_at
-- and emits an envelope warning if the most recent successful refresh is
-- more than 24h ago. Nullable because pre-Phase-13 runs never touched it.
ALTER TABLE earning_schedule_run
    ADD COLUMN IF NOT EXISTS contrib_presence_refresh_at TIMESTAMPTZ;

COMMENT ON COLUMN earning_schedule_run.contrib_presence_refresh_at IS
    'Phase 13 §C per grill note 4: set by refreshMemberContributionPresence on the '
    'row for the current nightly run. NULL for pre-Phase-13 runs and for a run whose '
    'refresh step failed. PersistencyCohortReportService reads the newest run row.';
