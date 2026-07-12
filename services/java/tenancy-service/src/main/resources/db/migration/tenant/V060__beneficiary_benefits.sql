-- =====================================================================
-- V060: beneficiary_benefits — per-member/dependant benefit utilization
-- =====================================================================
-- Each row snapshots one benefit-year for one beneficiary. Populated at
-- enrolment (member) or add-dependant time (dependant); consumed_amount
-- + consumed_count accumulate every time a claim line against this
-- benefit is accepted by the adjudicator. Lets the claim-detail page
-- render "used $180 of $500 optical this year" per beneficiary
-- without a per-page-load aggregation over claims + claim_lines.
--
-- Cardinality: (member_id, dependant_id, benefit_id, policy_year) is
-- unique. dependant_id is NULL when the beneficiary IS the member; a
-- partial unique index handles that (Postgres treats each NULL as
-- distinct in a plain UNIQUE, which would let a member accrue two
-- rows per benefit-year — a UNIQUE on the COALESCE guards it).
-- =====================================================================

CREATE TABLE beneficiary_benefits (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    member_id         UUID NOT NULL REFERENCES members(id) ON DELETE CASCADE,
    dependant_id      UUID REFERENCES dependants(id) ON DELETE CASCADE,
    benefit_id        UUID NOT NULL REFERENCES scheme_benefits(id) ON DELETE CASCADE,
    scheme_id         UUID NOT NULL REFERENCES schemes(id),
    policy_year       INT  NOT NULL,
    consumed_amount   DECIMAL(19, 4) NOT NULL DEFAULT 0,
    consumed_count    INT  NOT NULL DEFAULT 0,
    currency_code     CHAR(3) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Uniqueness that treats NULL dependant_id as "the member themselves"
-- rather than as its own distinct key per row.
CREATE UNIQUE INDEX ux_bb_beneficiary_benefit_year ON beneficiary_benefits
    (member_id, COALESCE(dependant_id, '00000000-0000-0000-0000-000000000000'::uuid),
     benefit_id, policy_year);

CREATE INDEX idx_bb_member       ON beneficiary_benefits(member_id);
CREATE INDEX idx_bb_dependant    ON beneficiary_benefits(dependant_id)
    WHERE dependant_id IS NOT NULL;
CREATE INDEX idx_bb_benefit_year ON beneficiary_benefits(benefit_id, policy_year);

-- ── Backfill existing beneficiaries ──────────────────────────────────
-- Every active member × every active benefit on their scheme (for the
-- current calendar year); same for every active dependant. Zero
-- consumption to start — the adjudication hook fills it in going
-- forward, and a later backfill can walk historical claim_lines to
-- retroactively populate consumption.

INSERT INTO beneficiary_benefits
    (member_id, dependant_id, benefit_id, scheme_id, policy_year, currency_code)
SELECT m.id,
       NULL,
       sb.id,
       m.scheme_id,
       EXTRACT(YEAR FROM CURRENT_DATE)::int,
       sb.currency_code
  FROM members m
  JOIN scheme_benefits sb ON sb.scheme_id = m.scheme_id
 WHERE m.scheme_id IS NOT NULL
   AND m.status IN ('active', 'enrolled')
ON CONFLICT DO NOTHING;

INSERT INTO beneficiary_benefits
    (member_id, dependant_id, benefit_id, scheme_id, policy_year, currency_code)
SELECT d.member_id,
       d.id,
       sb.id,
       m.scheme_id,
       EXTRACT(YEAR FROM CURRENT_DATE)::int,
       sb.currency_code
  FROM dependants d
  JOIN members m ON m.id = d.member_id
  JOIN scheme_benefits sb ON sb.scheme_id = m.scheme_id
 WHERE m.scheme_id IS NOT NULL
   AND (d.status IS NULL OR d.status IN ('active', 'enrolled'))
ON CONFLICT DO NOTHING;
