package com.medfund.contributions.service.candidate;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * LIFE candidate resolver. Person-insuring — joins {@code life_policies}
 * to {@code members} so the candidate's person_name is the member's
 * full name and member_number is the policy_number (audit's friendly
 * identifier per feedback_audit_entity_name).
 *
 * <p>{@code insured_member_id} is NOT NULL per V032, so every life
 * policy bills against a real member. Status filter includes active
 * + suspended, matching the HEALTH resolver's lenient policy so
 * suspended members still get a preview row (commit-time logic
 * decides whether to skip-or-warn).
 *
 * <p>Pricing COALESCE: billing_override (INDIVIDUAL only) → scheme
 * default_premium → 0.
 */
@Component
public class LifeCandidateResolver implements CandidateResolver {

    private final DatabaseClient db;

    public LifeCandidateResolver(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public String supportedLine() {
        return "LIFE";
    }

    @Override
    public Flux<PersonCandidate> resolveCandidates(List<UUID> groupIds, List<UUID> memberIds,
                                                    LocalDate periodStart, LocalDate periodEnd,
                                                    String pricingModel) {
        StringBuilder sql = new StringBuilder("""
                SELECT lp.insured_member_id   AS member_id,
                       NULL::uuid             AS dependant_id,
                       lp.policy_number       AS member_number,
                       (m.first_name || ' ' || m.last_name) AS person_name,
                       'LIFE_POLICY'          AS person_type,
                       lp.scheme_id           AS scheme_id,
                       s.name                 AS scheme_name,
                       lp.group_id            AS group_id,
                       g.name                 AS group_name,
                       s.currency_code        AS scheme_currency,
                       NULL::uuid             AS effective_age_group_id,
                       NULL::text             AS age_band_name,
                       COALESCE(
                           CASE WHEN :pricingModel = 'INDIVIDUAL'
                                     AND lp.billing_override_amount IS NOT NULL
                                     AND (lp.billing_override_effective_from IS NULL
                                          OR lp.billing_override_effective_from <= :periodEnd)
                                THEN lp.billing_override_amount END,
                           s.default_premium,
                           0)                 AS price_amount,
                       s.currency_code        AS price_currency
                  FROM life_policies lp
                  JOIN members m     ON m.id = lp.insured_member_id
                  JOIN schemes s     ON s.id = lp.scheme_id
                  LEFT JOIN groups g ON g.id = lp.group_id
                 WHERE lp.status IN ('active', 'suspended')
                   AND m.status IN ('active', 'suspended')
                   AND (lp.group_id IS NULL OR g.status = 'active')
                   AND lp.created_at::date <= :periodEnd
                """);
        if (groupIds  != null && !groupIds.isEmpty())  sql.append(" AND lp.group_id = ANY(:groupIds) ");
        if (memberIds != null && !memberIds.isEmpty()) sql.append(" AND lp.insured_member_id = ANY(:memberIds) ");

        var spec = db.sql(sql.toString())
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
