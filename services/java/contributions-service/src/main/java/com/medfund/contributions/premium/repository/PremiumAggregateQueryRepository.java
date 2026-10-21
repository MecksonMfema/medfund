package com.medfund.contributions.premium.repository;

import com.medfund.contributions.premium.dto.PremiumEarnedAggregateRow;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 18 K8 aggregate query — earned premium per (currency [, insurance
 * line [, scheme]]) for a period. Reads only closed periods (period_end
 * inside the window and earned_at_period_end IS NOT NULL). Nightly
 * PremiumEarningExecutor guarantees earned_at_period_end is populated on
 * closure — periods that haven't closed contribute zero rows and the KPI
 * page surfaces a "reflects fully-closed periods only" warning.
 *
 * <p>Dimension enum drives the GROUP BY: TENANT groups by currency only;
 * LINE adds insurance_line; SCHEME joins the seven-way policy_enrichment
 * CTE mirroring {@code PremiumReportQueryRepository:211-249} to unpack
 * ({@code policy_id, policy_source}) into {@code scheme_id}.
 *
 * <p>Table names are unqualified — {@code earning_schedule}, {@code
 * schemes}, and the six line-specific policy tables live in the tenant
 * schema. See {@code bug_public_prefix_silent_rollback} in project memory.
 */
@Repository
@RequiredArgsConstructor
public class PremiumAggregateQueryRepository {

    private final DatabaseClient databaseClient;

    public enum Dimension { TENANT, LINE, SCHEME }

    public Flux<PremiumEarnedAggregateRow> earnedPremium(
            LocalDate periodStart,
            LocalDate periodEnd,
            Dimension dimension,
            String insuranceLineFilter,
            UUID schemeIdFilter) {

        StringBuilder sql = new StringBuilder();
        if (dimension == Dimension.SCHEME) {
            sql.append("WITH policy_enrichment AS (\n").append(POLICY_ENRICHMENT_CTE).append("\n)\n");
        }
        sql.append("SELECT es.currency_code AS currency_code\n")
                .append("     , SUM(es.earned_at_period_end) AS earned_premium\n")
                .append("     , COUNT(*)                     AS row_count\n");
        if (dimension == Dimension.SCHEME) {
            sql.append("     , pe.scheme_id                  AS scheme_id\n")
                    .append("     , s.name                        AS scheme_name\n");
        } else {
            sql.append("     , CAST(NULL AS UUID)            AS scheme_id\n")
                    .append("     , CAST(NULL AS VARCHAR)         AS scheme_name\n");
        }
        if (dimension != Dimension.TENANT) {
            sql.append("     , es.insurance_line             AS insurance_line\n");
        } else {
            sql.append("     , CAST(NULL AS VARCHAR)         AS insurance_line\n");
        }
        sql.append("  FROM earning_schedule es\n");
        if (dimension == Dimension.SCHEME) {
            sql.append("  LEFT JOIN policy_enrichment pe\n")
                    .append("    ON pe.policy_id = es.policy_id AND pe.policy_source = es.policy_source\n")
                    .append("  LEFT JOIN schemes s ON s.id = pe.scheme_id\n");
        }
        sql.append(" WHERE es.period_end >= :periodStart\n")
                .append("   AND es.period_end <  :periodEnd\n")
                .append("   AND es.earned_at_period_end IS NOT NULL\n");
        if (insuranceLineFilter != null) {
            sql.append("   AND es.insurance_line = :insuranceLine\n");
        }
        if (schemeIdFilter != null && dimension == Dimension.SCHEME) {
            sql.append("   AND pe.scheme_id = :schemeId\n");
        }
        sql.append(switch (dimension) {
            case TENANT -> " GROUP BY es.currency_code\n";
            case LINE   -> " GROUP BY es.currency_code, es.insurance_line\n";
            case SCHEME -> " GROUP BY es.currency_code, es.insurance_line, pe.scheme_id, s.name\n";
        });

        DatabaseClient.GenericExecuteSpec spec = databaseClient.sql(sql.toString())
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd);
        if (insuranceLineFilter != null) {
            spec = spec.bind("insuranceLine", insuranceLineFilter);
        }
        if (schemeIdFilter != null && dimension == Dimension.SCHEME) {
            spec = spec.bind("schemeId", schemeIdFilter);
        }

        return spec.map((row, meta) -> new PremiumEarnedAggregateRow(
                        row.get("scheme_id", UUID.class),
                        row.get("scheme_name", String.class),
                        row.get("insurance_line", String.class),
                        row.get("currency_code", String.class),
                        nz(row.get("earned_premium", BigDecimal.class)),
                        row.get("row_count", Long.class) != null
                                ? row.get("row_count", Long.class)
                                : 0L))
                .all();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    // Seven-way UNION-ALL policy enrichment matching PremiumReportQueryRepository:211-249.
    // Uses the real table names for the asset lines: `vehicles` (not vehicle_policies),
    // `properties` (not property_policies). Selects only what the SCHEME dimension needs
    // (policy_id, policy_source, scheme_id).
    private static final String POLICY_ENRICHMENT_CTE = """
                SELECT id AS policy_id, 'LIFE_POLICY'::text       AS policy_source, scheme_id
                  FROM life_policies
                UNION ALL
                SELECT id, 'FUNERAL_POLICY'::text,   scheme_id FROM funeral_policies
                UNION ALL
                SELECT id, 'DISABILITY_POLICY'::text, scheme_id FROM disability_policies
                UNION ALL
                SELECT id, 'TRAVEL_POLICY'::text,    scheme_id FROM travel_policies
                UNION ALL
                SELECT id, 'VEHICLE_POLICY'::text,   scheme_id FROM vehicles
                UNION ALL
                SELECT id, 'PROPERTY_POLICY'::text,  scheme_id FROM properties
                UNION ALL
                SELECT id, 'CONTRIBUTION'::text,     scheme_id FROM contributions
            """;
}
