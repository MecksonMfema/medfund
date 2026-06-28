package com.medfund.contributions.service.candidate;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * PROPERTY candidate resolver. Projects active properties into
 * {@link PersonCandidate} with the property's name as both the
 * friendly identifier (member_number slot) and the human-readable
 * person_name. {@code owner_member_id} nullable for corporate
 * portfolios — see {@link VehicleCandidateResolver} for the same
 * pattern.
 *
 * <p>Pricing COALESCE: billing_override (INDIVIDUAL only) → scheme
 * default_premium → 0.
 */
@Component
public class PropertyCandidateResolver implements CandidateResolver {

    private final DatabaseClient db;

    public PropertyCandidateResolver(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public String supportedLine() {
        return "PROPERTY";
    }

    @Override
    public Flux<PersonCandidate> resolveCandidates(List<UUID> groupIds, List<UUID> memberIds,
                                                    LocalDate periodStart, LocalDate periodEnd,
                                                    String pricingModel) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.owner_member_id     AS member_id,
                       NULL::uuid            AS dependant_id,
                       p.property_name       AS member_number,
                       p.property_name       AS person_name,
                       'PROPERTY'            AS person_type,
                       p.scheme_id           AS scheme_id,
                       s.name                AS scheme_name,
                       p.group_id            AS group_id,
                       g.name                AS group_name,
                       s.currency_code       AS scheme_currency,
                       NULL::uuid            AS effective_age_group_id,
                       NULL::text            AS age_band_name,
                       COALESCE(
                           CASE WHEN :pricingModel = 'INDIVIDUAL'
                                     AND p.billing_override_amount IS NOT NULL
                                     AND (p.billing_override_effective_from IS NULL
                                          OR p.billing_override_effective_from <= :periodEnd)
                                THEN p.billing_override_amount END,
                           s.default_premium,
                           0)                AS price_amount,
                       s.currency_code       AS price_currency
                  FROM properties p
                  JOIN schemes s     ON s.id = p.scheme_id
                  LEFT JOIN groups g ON g.id = p.group_id
                 WHERE p.status IN ('active', 'suspended')
                   AND (p.group_id IS NULL OR g.status = 'active')
                   AND p.created_at::date <= :periodEnd
                """);
        if (groupIds  != null && !groupIds.isEmpty())  sql.append(" AND p.group_id = ANY(:groupIds) ");
        if (memberIds != null && !memberIds.isEmpty()) sql.append(" AND p.owner_member_id = ANY(:memberIds) ");

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
