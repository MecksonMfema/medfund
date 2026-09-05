package com.medfund.claims.repository;

import com.medfund.claims.dto.ClaimsIncurredAggregateRow;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 18 K9 aggregate — incurred claims (paid + reserve movement) per
 * (currency [, insurance line [, scheme]]) for a period. Reserve balances
 * at each boundary use {@code DISTINCT ON (claim_id)} against
 * {@link #RESERVE_HISTORY_TABLE} — the idiomatic PostgreSQL way to grab
 * the latest row per group.
 *
 * <p>The period clock is {@code claims.adjudicated_at} — matching the
 * existing cross-service aggregate convention ({@code
 * ClaimsReportQueryRepository.CLAIMS_PERIOD}). Plan §Phase 3 initially
 * named {@code c.paid_at} and {@code claim_details.paid_amount} but
 * neither exists on the tenant schema: {@code claims.paid_amount} is a
 * direct column (foreign-written by finance-service's payment-run flow)
 * and there is no {@code claim_details} table, so we sum the column
 * directly.
 */
@Repository
@RequiredArgsConstructor
public class ClaimsAggregateQueryRepository {

    private final DatabaseClient databaseClient;

    public enum Dimension { TENANT, LINE, SCHEME }

    public Flux<ClaimsIncurredAggregateRow> claimsIncurred(
            LocalDate periodStart,
            LocalDate periodEnd,
            Dimension dimension,
            String insuranceLineFilter,
            UUID schemeIdFilter) {

        StringBuilder sql = new StringBuilder();
        sql.append("""
                WITH reserve_balance_at_start AS (
                    SELECT DISTINCT ON (claim_id)
                           claim_id, reserved_amount
                      FROM claim_reserve_history
                     WHERE effective_at < :periodStart
                     ORDER BY claim_id, effective_at DESC
                ),
                reserve_balance_at_end AS (
                    SELECT DISTINCT ON (claim_id)
                           claim_id, reserved_amount
                      FROM claim_reserve_history
                     WHERE effective_at < :periodEnd
                     ORDER BY claim_id, effective_at DESC
                ),
                claims_in_period AS (
                    SELECT c.id             AS claim_id,
                           c.currency_code  AS currency_code,
                           c.insurance_line AS insurance_line,
                           c.scheme_id      AS scheme_id,
                           COALESCE(c.paid_amount, 0) AS paid_amount
                      FROM claims c
                     WHERE c.adjudicated_at >= :periodStart
                       AND c.adjudicated_at <  :periodEnd
                """);
        if (insuranceLineFilter != null) {
            sql.append("       AND c.insurance_line = :insuranceLine\n");
        }
        if (schemeIdFilter != null && dimension == Dimension.SCHEME) {
            sql.append("       AND c.scheme_id = :schemeId\n");
        }
        sql.append(")\n");

        sql.append("SELECT p.currency_code AS currency_code\n");
        if (dimension != Dimension.TENANT) {
            sql.append("     , p.insurance_line AS insurance_line\n");
        } else {
            sql.append("     , CAST(NULL AS VARCHAR) AS insurance_line\n");
        }
        if (dimension == Dimension.SCHEME) {
            sql.append("     , p.scheme_id AS scheme_id\n")
                    .append("     , s.name       AS scheme_name\n");
        } else {
            sql.append("     , CAST(NULL AS UUID)    AS scheme_id\n")
                    .append("     , CAST(NULL AS VARCHAR) AS scheme_name\n");
        }
        sql.append("""
                     , COALESCE(SUM(p.paid_amount), 0)                                           AS total_paid
                     , COALESCE(SUM(rs.reserved_amount), 0)                                      AS reserve_balance_start
                     , COALESCE(SUM(re.reserved_amount), 0)                                      AS reserve_balance_end
                     , COALESCE(SUM(re.reserved_amount), 0) - COALESCE(SUM(rs.reserved_amount), 0) AS reserve_movement
                     , COALESCE(SUM(p.paid_amount), 0)
                         + COALESCE(SUM(re.reserved_amount), 0)
                         - COALESCE(SUM(rs.reserved_amount), 0)                                  AS subtotal_incurred_ex_ibnr
                     , COUNT(DISTINCT p.claim_id)                                                AS claim_count
                  FROM claims_in_period p
                  LEFT JOIN reserve_balance_at_start rs ON rs.claim_id = p.claim_id
                  LEFT JOIN reserve_balance_at_end   re ON re.claim_id = p.claim_id
                """);
        if (dimension == Dimension.SCHEME) {
            sql.append("  LEFT JOIN schemes s ON s.id = p.scheme_id\n");
        }
        sql.append(switch (dimension) {
            case TENANT -> " GROUP BY p.currency_code\n";
            case LINE   -> " GROUP BY p.currency_code, p.insurance_line\n";
            case SCHEME -> " GROUP BY p.currency_code, p.insurance_line, p.scheme_id, s.name\n";
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

        return spec.map((row, meta) -> new ClaimsIncurredAggregateRow(
                        row.get("scheme_id", UUID.class),
                        row.get("scheme_name", String.class),
                        row.get("insurance_line", String.class),
                        row.get("currency_code", String.class),
                        nz(row.get("total_paid", BigDecimal.class)),
                        nz(row.get("reserve_balance_start", BigDecimal.class)),
                        nz(row.get("reserve_balance_end", BigDecimal.class)),
                        nz(row.get("reserve_movement", BigDecimal.class)),
                        nz(row.get("subtotal_incurred_ex_ibnr", BigDecimal.class)),
                        row.get("claim_count", Long.class) != null
                                ? row.get("claim_count", Long.class)
                                : 0L))
                .all();
    }

    private static BigDecimal nz(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static final String RESERVE_HISTORY_TABLE = "claim_reserve_history";
}
