package com.medfund.contributions.service.candidate;

import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * MOTOR (VEHICLE) candidate resolver. Projects active vehicles into
 * {@link PersonCandidate} with the asset's registration number as the
 * friendly identifier (member_number slot) and "{make} {model} ({year})"
 * as the human-readable name. {@code owner_member_id} is nullable —
 * corporate portfolios without a per-asset owner still bill cleanly;
 * the candidate's {@code memberId} just stays null in those rows.
 *
 * <p>Pricing follows the line-agnostic COALESCE:
 * <pre>
 *   billing_override_amount (if INDIVIDUAL mode and effective_from reached)
 *   ↓ else
 *   schemes.default_premium  (V033 — the flat fallback for non-HEALTH)
 *   ↓ else
 *   0  (data-gap surfaced as a zero-priced preview row)
 * </pre>
 * The AI_DRIVEN mode multiplies this by the per-candidate risk
 * multiplier from the ai-service /pricing/score endpoint downstream.
 *
 * <p>No age-group concept for MOTOR; {@code effectiveAgeGroupId} and
 * {@code ageBandName} stay null.
 */
@Component
public class VehicleCandidateResolver implements CandidateResolver {

    private final DatabaseClient db;

    public VehicleCandidateResolver(DatabaseClient db) {
        this.db = db;
    }

    @Override
    public String supportedLine() {
        return "VEHICLE";
    }

    @Override
    public Flux<PersonCandidate> resolveCandidates(List<UUID> groupIds, List<UUID> memberIds,
                                                    LocalDate periodStart, LocalDate periodEnd,
                                                    String pricingModel) {
        StringBuilder sql = new StringBuilder("""
                SELECT v.owner_member_id     AS member_id,
                       NULL::uuid            AS dependant_id,
                       v.registration_number AS member_number,
                       (v.make || ' ' || v.model || ' (' || v.year::text || ')') AS person_name,
                       'VEHICLE'             AS person_type,
                       v.scheme_id           AS scheme_id,
                       s.name                AS scheme_name,
                       v.group_id            AS group_id,
                       g.name                AS group_name,
                       s.currency_code       AS scheme_currency,
                       NULL::uuid            AS effective_age_group_id,
                       NULL::text            AS age_band_name,
                       COALESCE(
                           CASE WHEN :pricingModel = 'INDIVIDUAL'
                                     AND v.billing_override_amount IS NOT NULL
                                     AND (v.billing_override_effective_from IS NULL
                                          OR v.billing_override_effective_from <= :periodEnd)
                                THEN v.billing_override_amount END,
                           s.default_premium,
                           0)                AS price_amount,
                       s.currency_code       AS price_currency
                  FROM vehicles v
                  JOIN schemes s     ON s.id = v.scheme_id
                  LEFT JOIN groups g ON g.id = v.group_id
                 WHERE v.status IN ('active', 'suspended')
                   AND (v.group_id IS NULL OR g.status = 'active')
                   AND v.created_at::date <= :periodEnd
                """);
        if (groupIds  != null && !groupIds.isEmpty())  sql.append(" AND v.group_id = ANY(:groupIds) ");
        if (memberIds != null && !memberIds.isEmpty()) sql.append(" AND v.owner_member_id = ANY(:memberIds) ");

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
