package com.medfund.contributions.service.candidate;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * HEALTH-line candidate resolver. Projects active members + active
 * dependants into the {@link PersonCandidate} row contract, with the
 * effective age-group resolved via {@code COALESCE(billing_override,
 * canonical)} gated by the override's effective_from date.
 *
 * <p>Pricing comes from the {@code age_group_prices} LATERAL join —
 * whichever historical price row was active for the billing period
 * wins. Per-member {@code billing_override_amount} short-circuits
 * the age-group price when the tenant's {@code pricing_model} is
 * {@code INDIVIDUAL} and the override's effective_from has been
 * reached for the period.
 *
 * <p>This is the SQL that lived in {@code BillingService.resolveCandidates}
 * before Part 1 of the multi-line plan; moved here verbatim so the
 * dispatcher can route by insurance_line without an if/else for each
 * new line.
 */
@Component
public class HealthCandidateResolver implements CandidateResolver {

    private final DatabaseClient db;

    public HealthCandidateResolver(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public String supportedLine() {
        return "HEALTH";
    }

    @Override
    public Flux<PersonCandidate> resolveCandidates(List<UUID> groupIds, List<UUID> memberIds,
                                                    LocalDate periodStart, LocalDate periodEnd,
                                                    String pricingModel) {
        StringBuilder sql = new StringBuilder("""
                SELECT m.id                AS member_id,
                       NULL::uuid          AS dependant_id,
                       m.member_number     AS member_number,
                       (m.first_name || ' ' || m.last_name) AS person_name,
                       'MEMBER'            AS person_type,
                       m.scheme_id         AS scheme_id,
                       m.group_id          AS group_id,
                       g.name              AS group_name,
                       s.name              AS scheme_name,
                       s.currency_code     AS scheme_currency,
                       -- effective_age_group_id precedence (V048):
                       --   1. billing_age_group_id (manual override, if the
                       --      override's effective_from has been reached).
                       --   2. age_group_id stamped at enrolment/insert.
                       --   3. Dynamic lookup by DOB against the scheme's
                       --      current bands. Load-bearing for members
                       --      created BEFORE a band was added to the
                       --      scheme — their stamp is null, and without
                       --      this fallback they'd fail "no band matched"
                       --      forever until the operator manually re-
                       --      stamped every row.
                       COALESCE(
                           CASE WHEN m.billing_age_group_id IS NOT NULL
                                     AND (m.billing_override_effective_from IS NULL
                                          OR m.billing_override_effective_from <= :periodStart)
                                THEN m.billing_age_group_id END,
                           m.age_group_id,
                           (SELECT ag_dyn.id FROM age_groups ag_dyn
                             WHERE ag_dyn.scheme_id = m.scheme_id
                               AND EXTRACT(YEAR FROM AGE(:periodStart, m.date_of_birth))::int
                                   BETWEEN ag_dyn.min_age AND ag_dyn.max_age
                             LIMIT 1)
                       )                    AS effective_age_group_id,
                       ag.name              AS age_band_name,
                       COALESCE(
                           -- Per-member override wins on both INDIVIDUAL and
                           -- AI_DRIVEN. AI_DRIVEN is a hybrid: same override
                           -- semantics as INDIVIDUAL, plus an AI suggestion
                           -- helper the operator uses at enrolment time to
                           -- pre-fill the amount. STANDARD tenants stay on
                           -- the age-band price (no override honoured).
                           CASE WHEN :pricingModel IN ('INDIVIDUAL', 'AI_DRIVEN')
                                     AND m.billing_override_amount IS NOT NULL
                                     AND (m.billing_override_effective_from IS NULL
                                          OR m.billing_override_effective_from <= :periodEnd)
                                THEN m.billing_override_amount END,
                           p.contribution_amount) AS price_amount,
                       p.currency_code       AS price_currency
                  FROM members m
                  JOIN schemes s     ON s.id = m.scheme_id
                  LEFT JOIN groups g ON g.id = m.group_id
                  LEFT JOIN age_groups ag ON ag.id = COALESCE(
                           CASE WHEN m.billing_age_group_id IS NOT NULL
                                     AND (m.billing_override_effective_from IS NULL
                                          OR m.billing_override_effective_from <= :periodStart)
                                THEN m.billing_age_group_id END,
                           m.age_group_id,
                           (SELECT ag_dyn.id FROM age_groups ag_dyn
                             WHERE ag_dyn.scheme_id = m.scheme_id
                               AND EXTRACT(YEAR FROM AGE(:periodStart, m.date_of_birth))::int
                                   BETWEEN ag_dyn.min_age AND ag_dyn.max_age
                             LIMIT 1)
                       )
                  LEFT JOIN LATERAL (
                       SELECT contribution_amount, currency_code
                         FROM age_group_prices
                        WHERE age_group_id = ag.id
                          AND effective_from <= :periodEnd
                          AND (effective_to IS NULL OR effective_to >= :periodStart)
                        ORDER BY effective_from DESC
                        LIMIT 1
                  ) p ON TRUE
                 -- Include 'enrolled' rows whose effective date lands in or
                 -- before the projected period (V048). Statements are
                 -- period-anchored: a member enrolling on Aug 1 should
                 -- appear on the August statement even while they're still
                 -- sitting at 'enrolled' during the July preview. The
                 -- enrollment_date guard below prevents any bill before
                 -- their cover starts.
                 WHERE m.status IN ('active', 'suspended', 'enrolled')
                   AND (m.group_id IS NULL OR g.status = 'active')
                   AND m.enrollment_date <= :periodEnd
                """);
        if (groupIds  != null && !groupIds.isEmpty())  sql.append(" AND m.group_id = ANY(:groupIds) ");
        if (memberIds != null && !memberIds.isEmpty()) sql.append(" AND m.id       = ANY(:memberIds) ");

        sql.append("""

                UNION ALL

                SELECT m.id                AS member_id,
                       d.id                AS dependant_id,
                       -- Prefer the dependant's own member_number (V036/V120 —
                       -- issued as "MBR-XXXXXX-02", "-03", …, or an
                       -- independent "DEP-XXXXXX" depending on the tenant's
                       -- scheme). Fall back to the parent's number only for
                       -- legacy pre-V036 rows where dependants weren't
                       -- issued a number at all.
                       COALESCE(d.member_number, m.member_number) AS member_number,
                       (d.first_name || ' ' || d.last_name) AS person_name,
                       'DEPENDANT'         AS person_type,
                       m.scheme_id         AS scheme_id,
                       m.group_id          AS group_id,
                       g.name              AS group_name,
                       s.name              AS scheme_name,
                       s.currency_code     AS scheme_currency,
                       -- V048 fallback (same rationale as the member half —
                       -- see the comment above). Applies when the Child
                       -- band gets added to the scheme after the dependant
                       -- was created; the stamp is null, but the dynamic
                       -- lookup now finds the band and prices correctly.
                       COALESCE(
                           CASE WHEN d.billing_age_group_id IS NOT NULL
                                     AND (d.billing_override_effective_from IS NULL
                                          OR d.billing_override_effective_from <= :periodStart)
                                THEN d.billing_age_group_id END,
                           d.age_group_id,
                           (SELECT ag_dyn.id FROM age_groups ag_dyn
                             WHERE ag_dyn.scheme_id = m.scheme_id
                               AND EXTRACT(YEAR FROM AGE(:periodStart, d.date_of_birth))::int
                                   BETWEEN ag_dyn.min_age AND ag_dyn.max_age
                             LIMIT 1)
                       )                    AS effective_age_group_id,
                       ag.name              AS age_band_name,
                       COALESCE(
                           -- Same INDIVIDUAL/AI_DRIVEN gate as the member half.
                           CASE WHEN :pricingModel IN ('INDIVIDUAL', 'AI_DRIVEN')
                                     AND d.billing_override_amount IS NOT NULL
                                     AND (d.billing_override_effective_from IS NULL
                                          OR d.billing_override_effective_from <= :periodEnd)
                                THEN d.billing_override_amount END,
                           p.contribution_amount) AS price_amount,
                       p.currency_code       AS price_currency
                  FROM dependants d
                  JOIN members m     ON m.id = d.member_id
                  JOIN schemes s     ON s.id = m.scheme_id
                  LEFT JOIN groups g ON g.id = m.group_id
                  LEFT JOIN age_groups ag ON ag.id = COALESCE(
                           CASE WHEN d.billing_age_group_id IS NOT NULL
                                     AND (d.billing_override_effective_from IS NULL
                                          OR d.billing_override_effective_from <= :periodStart)
                                THEN d.billing_age_group_id END,
                           d.age_group_id,
                           (SELECT ag_dyn.id FROM age_groups ag_dyn
                             WHERE ag_dyn.scheme_id = m.scheme_id
                               AND EXTRACT(YEAR FROM AGE(:periodStart, d.date_of_birth))::int
                                   BETWEEN ag_dyn.min_age AND ag_dyn.max_age
                             LIMIT 1)
                       )
                  LEFT JOIN LATERAL (
                       SELECT contribution_amount, currency_code
                         FROM age_group_prices
                        WHERE age_group_id = ag.id
                          AND effective_from <= :periodEnd
                          AND (effective_to IS NULL OR effective_to >= :periodStart)
                        ORDER BY effective_from DESC
                        LIMIT 1
                  ) p ON TRUE
                 -- Dependant status filter mirrors the member half —
                 -- 'enrolled' rows are included when their effective date
                 -- lands in or before the projected period (V048). Both
                 -- guards (enrollment_date on the row, enrollment_date on
                 -- the parent member) still stop the bill from firing
                 -- before cover starts.
                 WHERE (
                       d.status IN ('active', 'suspended', 'enrolled')
                       OR (d.status = 'deactivated'
                           AND (d.deactivation_effective_date IS NULL
                                OR d.deactivation_effective_date >= :periodStart))
                       )
                   AND m.status IN ('active', 'suspended', 'enrolled')
                   AND (m.group_id IS NULL OR g.status = 'active')
                   AND m.enrollment_date <= :periodEnd
                   AND d.enrollment_date <= :periodEnd
                """);
        if (groupIds  != null && !groupIds.isEmpty())  sql.append(" AND m.group_id = ANY(:groupIds) ");
        if (memberIds != null && !memberIds.isEmpty()) sql.append(" AND m.id       = ANY(:memberIds) ");

        var spec = db.sql(sql.toString())
                .bind("periodStart",  periodStart)
                .bind("periodEnd",    periodEnd)
                .bind("pricingModel", pricingModel != null ? pricingModel : "STANDARD");
        if (groupIds  != null && !groupIds.isEmpty())  spec = spec.bind("groupIds",  groupIds.toArray(UUID[]::new));
        if (memberIds != null && !memberIds.isEmpty()) spec = spec.bind("memberIds", memberIds.toArray(UUID[]::new));

        return spec.map(row -> new PersonCandidate(
                        row.get("member_id", UUID.class),
                        row.get("dependant_id", UUID.class),
                        row.get("member_number", String.class),
                        row.get("person_name", String.class),
                        row.get("person_type", String.class),
                        row.get("scheme_id", UUID.class),
                        row.get("scheme_name", String.class),
                        row.get("group_id", UUID.class),
                        row.get("group_name", String.class),
                        row.get("scheme_currency", String.class),
                        row.get("effective_age_group_id", UUID.class),
                        row.get("age_band_name", String.class),
                        row.get("price_amount", BigDecimal.class),
                        row.get("price_currency", String.class)))
                .all();
    }
}
