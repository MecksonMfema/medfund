package com.medfund.contributions.service.candidate;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * FUNERAL candidate resolver. Person-insuring — joins
 * {@code funeral_policies} to {@code members} via principal_member_id
 * (NOT NULL FK per V032). Policy_number is the audit-friendly
 * identifier in the member_number slot.
 */
@Component
public class FuneralCandidateResolver implements CandidateResolver {

    private final DatabaseClient db;

    public FuneralCandidateResolver(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public String supportedLine() {
        return "FUNERAL";
    }

    @Override
    public Flux<PersonCandidate> resolveCandidates(List<UUID> groupIds, List<UUID> memberIds,
                                                    LocalDate periodStart, LocalDate periodEnd,
                                                    String pricingModel) {
        StringBuilder sql = new StringBuilder("""
                SELECT fp.principal_member_id AS member_id,
                       NULL::uuid             AS dependant_id,
                       fp.policy_number       AS member_number,
                       (m.first_name || ' ' || m.last_name) AS person_name,
                       'FUNERAL_POLICY'       AS person_type,
                       fp.scheme_id           AS scheme_id,
                       s.name                 AS scheme_name,
                       fp.group_id            AS group_id,
                       g.name                 AS group_name,
                       s.currency_code        AS scheme_currency,
                       NULL::uuid             AS effective_age_group_id,
                       NULL::text             AS age_band_name,
                       COALESCE(
                           CASE WHEN :pricingModel = 'INDIVIDUAL'
                                     AND fp.billing_override_amount IS NOT NULL
                                     AND (fp.billing_override_effective_from IS NULL
                                          OR fp.billing_override_effective_from <= :periodEnd)
                                THEN fp.billing_override_amount END,
                           s.default_premium,
                           0)                 AS price_amount,
                       s.currency_code        AS price_currency
                  FROM funeral_policies fp
                  JOIN members m     ON m.id = fp.principal_member_id
                  JOIN schemes s     ON s.id = fp.scheme_id
                  LEFT JOIN groups g ON g.id = fp.group_id
                 WHERE fp.status IN ('active', 'suspended')
                   AND m.status IN ('active', 'suspended')
                   AND (fp.group_id IS NULL OR g.status = 'active')
                   AND fp.created_at::date <= :periodEnd
                """);
        if (groupIds  != null && !groupIds.isEmpty())  sql.append(" AND fp.group_id = ANY(:groupIds) ");
        if (memberIds != null && !memberIds.isEmpty()) sql.append(" AND fp.principal_member_id = ANY(:memberIds) ");

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
