package com.medfund.contributions.service.candidate;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * DISABILITY candidate resolver. Person-insuring — joins
 * {@code disability_policies} to {@code members} via
 * insured_member_id (NOT NULL FK per V032).
 */
@Component
public class DisabilityCandidateResolver implements CandidateResolver {

    private final DatabaseClient db;

    public DisabilityCandidateResolver(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public String supportedLine() {
        return "DISABILITY";
    }

    @Override
    public Flux<PersonCandidate> resolveCandidates(List<UUID> groupIds, List<UUID> memberIds,
                                                    LocalDate periodStart, LocalDate periodEnd,
                                                    String pricingModel) {
        StringBuilder sql = new StringBuilder("""
                SELECT dp.insured_member_id   AS member_id,
                       NULL::uuid             AS dependant_id,
                       dp.policy_number       AS member_number,
                       (m.first_name || ' ' || m.last_name) AS person_name,
                       'DISABILITY_POLICY'    AS person_type,
                       dp.scheme_id           AS scheme_id,
                       s.name                 AS scheme_name,
                       dp.group_id            AS group_id,
                       g.name                 AS group_name,
                       s.currency_code        AS scheme_currency,
                       NULL::uuid             AS effective_age_group_id,
                       NULL::text             AS age_band_name,
                       COALESCE(
                           CASE WHEN :pricingModel = 'INDIVIDUAL'
                                     AND dp.billing_override_amount IS NOT NULL
                                     AND (dp.billing_override_effective_from IS NULL
                                          OR dp.billing_override_effective_from <= :periodEnd)
                                THEN dp.billing_override_amount END,
                           s.default_premium,
                           0)                 AS price_amount,
                       s.currency_code        AS price_currency
                  FROM disability_policies dp
                  JOIN members m     ON m.id = dp.insured_member_id
                  JOIN schemes s     ON s.id = dp.scheme_id
                  LEFT JOIN groups g ON g.id = dp.group_id
                 WHERE dp.status IN ('active', 'suspended')
                   AND m.status IN ('active', 'suspended', 'enrolled')
                   AND (dp.group_id IS NULL OR g.status = 'active')
                   AND dp.created_at::date <= :periodEnd
                """);
        if (groupIds  != null && !groupIds.isEmpty())  sql.append(" AND dp.group_id = ANY(:groupIds) ");
        if (memberIds != null && !memberIds.isEmpty()) sql.append(" AND dp.insured_member_id = ANY(:memberIds) ");

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
