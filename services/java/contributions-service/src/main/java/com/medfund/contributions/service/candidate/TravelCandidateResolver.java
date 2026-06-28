package com.medfund.contributions.service.candidate;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * TRAVEL candidate resolver. Person-insuring — joins
 * {@code travel_policies} to {@code members} via traveler_member_id
 * (NOT NULL FK per V032).
 *
 * <p>Trip-window filter: a policy is billable for the period if its
 * trip overlaps [periodStart, periodEnd]. For v1 this uses the same
 * monthly billing cadence as the other lines; per-trip event billing
 * is a v2 enhancement (see plan Part 4.5 notes).
 */
@Component
public class TravelCandidateResolver implements CandidateResolver {

    private final DatabaseClient db;

    public TravelCandidateResolver(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public String supportedLine() {
        return "TRAVEL";
    }

    @Override
    public Flux<PersonCandidate> resolveCandidates(List<UUID> groupIds, List<UUID> memberIds,
                                                    LocalDate periodStart, LocalDate periodEnd,
                                                    String pricingModel) {
        StringBuilder sql = new StringBuilder("""
                SELECT tp.traveler_member_id  AS member_id,
                       NULL::uuid             AS dependant_id,
                       tp.policy_number       AS member_number,
                       (m.first_name || ' ' || m.last_name) AS person_name,
                       'TRAVEL_POLICY'        AS person_type,
                       tp.scheme_id           AS scheme_id,
                       s.name                 AS scheme_name,
                       tp.group_id            AS group_id,
                       g.name                 AS group_name,
                       s.currency_code        AS scheme_currency,
                       NULL::uuid             AS effective_age_group_id,
                       NULL::text             AS age_band_name,
                       COALESCE(
                           CASE WHEN :pricingModel = 'INDIVIDUAL'
                                     AND tp.billing_override_amount IS NOT NULL
                                     AND (tp.billing_override_effective_from IS NULL
                                          OR tp.billing_override_effective_from <= :periodEnd)
                                THEN tp.billing_override_amount END,
                           s.default_premium,
                           0)                 AS price_amount,
                       s.currency_code        AS price_currency
                  FROM travel_policies tp
                  JOIN members m     ON m.id = tp.traveler_member_id
                  JOIN schemes s     ON s.id = tp.scheme_id
                  LEFT JOIN groups g ON g.id = tp.group_id
                 WHERE tp.status IN ('active', 'suspended')
                   AND m.status IN ('active', 'suspended')
                   AND (tp.group_id IS NULL OR g.status = 'active')
                   AND tp.trip_start_date <= :periodEnd
                   AND tp.trip_end_date   >= :periodStart
                """);
        if (groupIds  != null && !groupIds.isEmpty())  sql.append(" AND tp.group_id = ANY(:groupIds) ");
        if (memberIds != null && !memberIds.isEmpty()) sql.append(" AND tp.traveler_member_id = ANY(:memberIds) ");

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
