-- =====================================================================
-- V027: Canonical age group + pricing-override columns on insured persons
-- =====================================================================
-- Records-sake vs billing-sake.
--   * age_group_id is the canonical bucket the person sits in based on
--     their age (set at signup, maintained as people cross a boundary).
--     Used for "how many adults / children / seniors do we cover" reports.
--   * billing_age_group_id is a nullable override the tenant admin sets
--     when a pricing flag applies (a lifelong-child case, a senior
--     discount on an adult, etc.). Billing reads
--     COALESCE(billing_age_group_id, age_group_id) so the canonical
--     record stays clean even when the rate the person is billed at
--     diverges from their actual age band.
--   * billing_override_reason carries the "why" so a future admin can
--     understand the override without re-tracing tickets.
--   * billing_override_effective_from is the date the override starts
--     applying — billing runs before that date keep using the canonical
--     bucket.
--
-- Both columns reference age_groups(id), which is scheme-scoped — when a
-- member's scheme changes, downstream code re-derives age_group_id (and,
-- if set, billing_age_group_id) against the new scheme's bands.
-- =====================================================================

ALTER TABLE members
    ADD COLUMN age_group_id                   uuid REFERENCES age_groups(id),
    ADD COLUMN billing_age_group_id           uuid REFERENCES age_groups(id),
    ADD COLUMN billing_override_reason        varchar(40),
    ADD COLUMN billing_override_effective_from date;

ALTER TABLE dependants
    ADD COLUMN age_group_id                   uuid REFERENCES age_groups(id),
    ADD COLUMN billing_age_group_id           uuid REFERENCES age_groups(id),
    ADD COLUMN billing_override_reason        varchar(40),
    ADD COLUMN billing_override_effective_from date;

-- Lookups on billing read the effective age group; an index on each id
-- column keeps the joins to age_groups cheap during preview and commit.
CREATE INDEX idx_members_age_group         ON members(age_group_id);
CREATE INDEX idx_members_billing_age_group ON members(billing_age_group_id);
CREATE INDEX idx_dependants_age_group         ON dependants(age_group_id);
CREATE INDEX idx_dependants_billing_age_group ON dependants(billing_age_group_id);

-- Backfill age_group_id for existing rows by joining on the scheme's
-- bands and the person's age in years against today. This is best-effort
-- — rows whose date_of_birth falls outside every band on their scheme
-- (gaps in the bands) stay NULL and surface as an enrolment data-quality
-- issue rather than silently being treated as zero.
--
-- date_of_birth lives on members directly and on dependants directly, so
-- the same shape works for both. Dependants inherit the parent member's
-- scheme — we resolve that via the join to members.
UPDATE members m
   SET age_group_id = ag.id
  FROM age_groups ag
 WHERE ag.scheme_id = m.scheme_id
   AND EXTRACT(YEAR FROM age(m.date_of_birth)) BETWEEN ag.min_age AND ag.max_age;

UPDATE dependants d
   SET age_group_id = ag.id
  FROM members m
  JOIN age_groups ag ON ag.scheme_id = m.scheme_id
 WHERE d.member_id = m.id
   AND EXTRACT(YEAR FROM age(d.date_of_birth)) BETWEEN ag.min_age AND ag.max_age;

COMMENT ON COLUMN members.age_group_id                   IS 'Canonical age bucket derived from date_of_birth at signup. Source of truth for "how many adults/children/seniors" reports.';
COMMENT ON COLUMN members.billing_age_group_id           IS 'Optional pricing override. When set, billing reads from this band instead of age_group_id.';
COMMENT ON COLUMN members.billing_override_reason        IS 'Why the override exists (LIFETIME_CHILD, SENIOR_DISCOUNT, …). Free-form per-tenant.';
COMMENT ON COLUMN members.billing_override_effective_from IS 'Override only applies to billing periods on or after this date.';
COMMENT ON COLUMN dependants.age_group_id                   IS 'Same semantics as members.age_group_id, scoped to the parent member''s scheme.';
COMMENT ON COLUMN dependants.billing_age_group_id           IS 'Same semantics as members.billing_age_group_id.';
COMMENT ON COLUMN dependants.billing_override_reason        IS 'Same semantics as members.billing_override_reason.';
COMMENT ON COLUMN dependants.billing_override_effective_from IS 'Same semantics as members.billing_override_effective_from.';
