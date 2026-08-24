package com.medfund.claims.reports.provider.repository;

import com.medfund.claims.reports.provider.dto.ProviderUtilizationRow;
import io.r2dbc.spi.Parameters;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Phase 13 §C Phase 9 — per-provider aggregate over the {@code claims}
 * table for a window. Emits rows keyed by (provider, insurance_line,
 * currency_code); {@code providerName} + {@code networkTier} are
 * placeholders here — the report service enriches them via a batched
 * {@code ProviderClient} call to user-service.
 *
 * <p>Adjudicated-window semantics (per G41): rows are filtered by
 * {@code adjudicated_at} inside [periodStart, periodEnd] so a claim only
 * counts once it's out of the WIP funnel.
 */
@Repository
@RequiredArgsConstructor
public class ProviderUtilizationQueryRepository {

    private final DatabaseClient db;

    public Flux<ProviderUtilizationRow> aggregate(LocalDate periodStart, LocalDate periodEnd,
                                                  String insuranceLine) {
        String sql = """
                SELECT provider_id,
                       insurance_line,
                       COALESCE(currency_code, 'USD') AS currency_code,
                       COUNT(*)                                    AS claim_count,
                       COALESCE(SUM(claimed_amount), 0)            AS total_claimed,
                       COALESCE(SUM(paid_amount), 0)               AS total_paid,
                       COUNT(*) FILTER (WHERE status = 'rejected') AS denial_count,
                       COUNT(DISTINCT member_id)                   AS unique_members
                  FROM claims
                 WHERE adjudicated_at IS NOT NULL
                   AND adjudicated_at >= :periodStart::timestamp
                   AND adjudicated_at <  (:periodEnd::timestamp + interval '1 day')
                   AND provider_id IS NOT NULL
                   AND (:insuranceLine::varchar IS NULL OR insurance_line = :insuranceLine::varchar)
                 GROUP BY provider_id, insurance_line, COALESCE(currency_code, 'USD')
                 ORDER BY total_paid DESC, provider_id
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .bind("insuranceLine", nullable(insuranceLine))
                .map((row, meta) -> new ProviderUtilizationRow(
                        row.get("provider_id", UUID.class),
                        null,   // providerName — enriched by service
                        "STANDARD",  // networkTier — enriched by service
                        row.get("insurance_line", String.class),
                        row.get("currency_code", String.class),
                        nonNull(row.get("claim_count", Long.class)),
                        nonNullBd(row.get("total_claimed", BigDecimal.class)),
                        nonNullBd(row.get("total_paid", BigDecimal.class)),
                        nonNull(row.get("denial_count", Long.class)),
                        nonNull(row.get("unique_members", Long.class))))
                .all();
    }

    private static long nonNull(Long v) { return v != null ? v : 0L; }
    private static BigDecimal nonNullBd(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }
    private static io.r2dbc.spi.Parameter nullable(String value) {
        return value == null || value.isBlank()
                ? Parameters.in(io.r2dbc.spi.R2dbcType.VARCHAR)
                : Parameters.in(value);
    }
}
