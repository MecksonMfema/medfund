-- V047 — Dependant enrollment date.
--
-- Business rule: a dependant's cover has an explicit start date — the
-- day the member's plan begins paying for them. Until now the
-- dashboard/resolver used dependants.created_at::date as a proxy
-- ("row exists → covered from that day"), which conflates the audit
-- timestamp with the business event. The two diverge whenever the
-- operator adds a dependant retroactively (e.g. a newborn added a
-- week after birth but effective from the birth date) or when a
-- dependant is enrolled effective a future month.
--
-- Semantics (matches members.enrollment_date):
--   * Always the 1st of a month — back-dating is allowed and the
--     contributions side posts arrears on any past-period start.
--   * NOT NULL; existing rows backfilled from created_at::date snapped
--     to the 1st of that month.
--   * The HealthCandidateResolver's dependant WHERE clause switches
--     from `d.created_at::date <= :periodEnd` to
--     `d.enrollment_date <= :periodEnd` so cover doesn't start before
--     the operator's chosen effective date.

ALTER TABLE dependants
    ADD COLUMN enrollment_date DATE;

-- Backfill: existing rows get their created_at truncated to the first
-- of the month. Same shape as V042's members.enrollment_date backfill.
UPDATE dependants
   SET enrollment_date = date_trunc('month', created_at)::date
 WHERE enrollment_date IS NULL;

ALTER TABLE dependants
    ALTER COLUMN enrollment_date SET NOT NULL;

-- Guard against day-in-the-middle-of-month drift on future writes.
-- Same CHECK shape members.enrollment_date_first_of_month uses.
ALTER TABLE dependants
    ADD CONSTRAINT dependants_enrollment_date_first_of_month
    CHECK (EXTRACT(DAY FROM enrollment_date) = 1);

CREATE INDEX idx_dependants_enrollment_date ON dependants(enrollment_date);

COMMENT ON COLUMN dependants.enrollment_date IS
    'Effective date the dependant became a beneficiary. Always the 1st of a month; back-dating triggers arrears on the contributions side. See HealthCandidateResolver — the dependant is only a billing candidate for cycles whose period_end >= enrollment_date.';
