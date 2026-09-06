-- Member death recording (actuarial Phase 14 §D) — feeds MORTALITY_STUDY
-- and stops exposure accrual for the affected member. Sits alongside the
-- existing termination_date column: a deceased member also gets terminated
-- via the Phase-13 MemberStatusTransitionService with reason_code='member_death'.
--
-- No index on death_date — MORTALITY_STUDY queries with an exposure-window
-- range scan across all members, not a point lookup.
-- No backfill — there is no prior death signal on the members table.

ALTER TABLE members
    ADD COLUMN IF NOT EXISTS death_date       DATE,
    ADD COLUMN IF NOT EXISTS cause_of_death   VARCHAR(80);
