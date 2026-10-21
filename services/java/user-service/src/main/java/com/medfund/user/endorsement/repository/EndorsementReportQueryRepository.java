package com.medfund.user.endorsement.repository;

import com.medfund.shared.report.PerCurrencyTotal;
import com.medfund.user.endorsement.dto.EndorsementRegisterRow;
import io.r2dbc.spi.Parameters;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * SQL projection for Phase 12 §C Phase 9 Endorsement Register. Rows come
 * from the {@code endorsement} table filtered by {@code effective_from}
 * within the reporting window; member names are enriched via a UNION-ALL
 * across the six annual-bind policy tables (each carries a
 * differently-named member-FK column) joined to {@code members}. Rows
 * without a resolvable member (e.g. a Vehicle owned by a Group) are still
 * returned — memberName just comes back {@code null}.
 *
 * <p>{@code perCurrency} totals aggregate the {@code |premiumDelta|}
 * magnitude per currency — signed sum would net a rate-card lift against
 * a mid-term cover reduction, which is not what the tenant admin wants to
 * see on the summary sheet.
 */
@Repository
@RequiredArgsConstructor
public class EndorsementReportQueryRepository {

    private final DatabaseClient db;

    /**
     * Endorsement rows for the window. Optional filters:
     * <ul>
     *   <li>{@code insuranceLine} — narrows to a single line.</li>
     *   <li>{@code status} — narrows to a single lifecycle status.</li>
     * </ul>
     */
    public Flux<EndorsementRegisterRow> rows(LocalDate periodStart, LocalDate periodEnd,
                                             String insuranceLine, String status) {
        String sql = """
                WITH policy_member AS (
                    SELECT id AS policy_id, 'LIFE_POLICY'::varchar AS policy_source, insured_member_id AS member_id
                      FROM life_policies
                    UNION ALL
                    SELECT id, 'FUNERAL_POLICY'::varchar, principal_member_id FROM funeral_policies
                    UNION ALL
                    SELECT id, 'DISABILITY_POLICY'::varchar, insured_member_id FROM disability_policies
                    UNION ALL
                    SELECT id, 'TRAVEL_POLICY'::varchar, traveler_member_id FROM travel_policies
                    UNION ALL
                    SELECT id, 'VEHICLE_POLICY'::varchar, owner_member_id FROM vehicles
                    UNION ALL
                    SELECT id, 'PROPERTY_POLICY'::varchar, owner_member_id FROM properties
                )
                SELECT e.id                    AS endorsement_id,
                       e.reference             AS reference,
                       e.policy_id             AS policy_id,
                       e.policy_source         AS policy_source,
                       (m.first_name || ' ' || m.last_name) AS member_name,
                       e.insurance_line        AS insurance_line,
                       e.change_type           AS change_type,
                       e.effective_from        AS effective_from,
                       e.premium_delta         AS premium_delta,
                       e.currency_code         AS currency_code,
                       e.status                AS status,
                       e.draft_actor_email     AS draft_actor_email,
                       e.draft_at              AS draft_at,
                       e.approve_actor_email   AS approve_actor_email,
                       e.approve_at            AS approve_at,
                       e.commit_actor_email    AS commit_actor_email,
                       e.commit_at             AS commit_at,
                       e.voided_reason         AS voided_reason
                  FROM endorsement e
                  LEFT JOIN policy_member pm
                         ON pm.policy_id = e.policy_id AND pm.policy_source = e.policy_source
                  LEFT JOIN members m ON m.id = pm.member_id
                 WHERE e.effective_from >= :periodStart
                   AND e.effective_from <= :periodEnd
                   AND (:insuranceLine::varchar IS NULL OR e.insurance_line = :insuranceLine::varchar)
                   AND (:status::varchar        IS NULL OR e.status         = :status::varchar)
                 ORDER BY e.effective_from, e.reference
                """;

        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .bind("insuranceLine", nullable(insuranceLine))
                .bind("status", nullable(status))
                .map((row, meta) -> new EndorsementRegisterRow(
                        row.get("endorsement_id", UUID.class),
                        row.get("reference", String.class),
                        row.get("policy_id", UUID.class),
                        row.get("policy_source", String.class),
                        row.get("member_name", String.class),
                        row.get("insurance_line", String.class),
                        row.get("change_type", String.class),
                        row.get("effective_from", LocalDate.class),
                        row.get("premium_delta", BigDecimal.class),
                        row.get("currency_code", String.class),
                        row.get("status", String.class),
                        row.get("draft_actor_email", String.class),
                        row.get("draft_at", Instant.class),
                        row.get("approve_actor_email", String.class),
                        row.get("approve_at", Instant.class),
                        row.get("commit_actor_email", String.class),
                        row.get("commit_at", Instant.class),
                        row.get("voided_reason", String.class)))
                .all();
    }

    /**
     * Per-currency aggregate over the same filter — sums the
     * {@code |premiumDelta|} magnitude so lifts and cuts don't cancel on
     * the summary line.
     */
    public Mono<Map<String, PerCurrencyTotal>> perCurrencyTotals(LocalDate periodStart, LocalDate periodEnd,
                                                                 String insuranceLine, String status) {
        String sql = """
                SELECT currency_code,
                       SUM(ABS(COALESCE(premium_delta, 0))) AS total_amount,
                       COUNT(*)                              AS row_count
                  FROM endorsement
                 WHERE effective_from >= :periodStart
                   AND effective_from <= :periodEnd
                   AND (:insuranceLine::varchar IS NULL OR insurance_line = :insuranceLine::varchar)
                   AND (:status::varchar        IS NULL OR status         = :status::varchar)
                   AND currency_code IS NOT NULL
                 GROUP BY currency_code
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .bind("insuranceLine", nullable(insuranceLine))
                .bind("status", nullable(status))
                .map((row, meta) -> Map.entry(
                        row.get("currency_code", String.class),
                        new PerCurrencyTotal(
                                row.get("total_amount", BigDecimal.class) != null
                                        ? row.get("total_amount", BigDecimal.class) : BigDecimal.ZERO,
                                row.get("row_count", Long.class) != null
                                        ? row.get("row_count", Long.class) : 0L)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue, LinkedHashMap::new)
                .map(m -> (Map<String, PerCurrencyTotal>) m);
    }

    private static io.r2dbc.spi.Parameter nullable(String value) {
        return value == null || value.isBlank()
                ? Parameters.in(io.r2dbc.spi.R2dbcType.VARCHAR)
                : Parameters.in(value);
    }
}
