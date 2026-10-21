package com.medfund.finance.reinsurance.repository;

import com.medfund.finance.reinsurance.dto.CessionBordereauRow;
import com.medfund.finance.reinsurance.dto.RecoveriesBordereauRow;
import com.medfund.finance.reinsurance.dto.TreatyUtilizationRow;
import com.medfund.shared.report.PerCurrencyTotal;
import io.r2dbc.spi.Parameters;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Raw-SQL projections for the three bordereau reports (Phase 10 §A):
 * cession, recoveries, treaty-utilization. All queries are participant-split
 * at read time — one row per {@code (cession, participant)} rather than one
 * per cession — per grill R9. Rows are native-currency (R7); FX conversion
 * happens only for the envelope grand-total scalar in the service layer.
 *
 * <p>Reinsurer + treaty filters are optional. NULL binds use
 * {@code Parameters.in(UUID.class)} so the {@code (:x::uuid IS NULL OR ...)}
 * predicates match every row when the filter is absent.
 */
@Repository
public class BordereauQueryRepository {

    private final DatabaseClient db;

    public BordereauQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    // ── Cession bordereau ───────────────────────────────────────────────────

    public Flux<CessionBordereauRow> cessionRows(UUID reinsurerId, UUID treatyId,
                                                 LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT c.id                                       AS cession_id,
                       c.treaty_id,
                       t.treaty_ref,
                       t.treaty_type,
                       r.id                                       AS reinsurer_id,
                       r.name                                     AS reinsurer_name,
                       tp.share_pct,
                       tp.share_role,
                       c.cession_type,
                       c.source,
                       c.occurred_at,
                       c.source_event_id,
                       c.source_event_type,
                       c.basis_amount                             AS native_basis,
                       c.ceded_amount                             AS native_ceded_full,
                       (c.ceded_amount * tp.share_pct / 100)      AS participant_ceded,
                       c.currency_code,
                       c.created_at
                  FROM cession c
                  JOIN treaty t              ON t.id = c.treaty_id
                  JOIN treaty_participant tp ON tp.treaty_id = t.id
                  JOIN reinsurer r           ON r.id = tp.reinsurer_id
                 WHERE c.status IN ('ACTIVE','CEDED')
                   AND c.occurred_at >= :periodStart
                   AND c.occurred_at <  :periodEnd
                   AND (:reinsurerId::uuid IS NULL OR r.id = :reinsurerId::uuid)
                   AND (:treatyId::uuid    IS NULL OR t.id = :treatyId::uuid)
                 ORDER BY t.treaty_ref, c.occurred_at, r.name
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .bind("periodEnd",   periodEnd.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .bind("reinsurerId", reinsurerId != null ? reinsurerId : Parameters.in(UUID.class))
                .bind("treatyId",    treatyId    != null ? treatyId    : Parameters.in(UUID.class))
                .map((row, meta) -> new CessionBordereauRow(
                        row.get("cession_id", UUID.class),
                        row.get("treaty_id", UUID.class),
                        row.get("treaty_ref", String.class),
                        row.get("treaty_type", String.class),
                        row.get("reinsurer_id", UUID.class),
                        row.get("reinsurer_name", String.class),
                        row.get("share_pct", BigDecimal.class),
                        row.get("share_role", String.class),
                        row.get("cession_type", String.class),
                        row.get("source", String.class),
                        row.get("occurred_at", OffsetDateTime.class),
                        row.get("source_event_id", UUID.class),
                        row.get("source_event_type", String.class),
                        row.get("native_basis", BigDecimal.class),
                        row.get("native_ceded_full", BigDecimal.class),
                        row.get("participant_ceded", BigDecimal.class),
                        row.get("currency_code", String.class),
                        row.get("created_at", OffsetDateTime.class),
                        false))
                .all();
    }

    public Mono<Map<String, PerCurrencyTotal>> cessionPerCurrencyTotals(
            UUID reinsurerId, UUID treatyId, LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT c.currency_code                                        AS currency_code,
                       SUM(c.ceded_amount * tp.share_pct / 100)               AS total_amount,
                       COUNT(*)                                               AS row_count
                  FROM cession c
                  JOIN treaty t              ON t.id = c.treaty_id
                  JOIN treaty_participant tp ON tp.treaty_id = t.id
                  JOIN reinsurer r           ON r.id = tp.reinsurer_id
                 WHERE c.status IN ('ACTIVE','CEDED')
                   AND c.occurred_at >= :periodStart
                   AND c.occurred_at <  :periodEnd
                   AND (:reinsurerId::uuid IS NULL OR r.id = :reinsurerId::uuid)
                   AND (:treatyId::uuid    IS NULL OR t.id = :treatyId::uuid)
                 GROUP BY c.currency_code
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .bind("periodEnd",   periodEnd.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .bind("reinsurerId", reinsurerId != null ? reinsurerId : Parameters.in(UUID.class))
                .bind("treatyId",    treatyId    != null ? treatyId    : Parameters.in(UUID.class))
                .map((row, meta) -> Map.entry(
                        row.get("currency_code", String.class),
                        new PerCurrencyTotal(
                                row.get("total_amount", BigDecimal.class) != null
                                        ? row.get("total_amount", BigDecimal.class)
                                        : BigDecimal.ZERO,
                                row.get("row_count", Long.class) != null
                                        ? row.get("row_count", Long.class)
                                        : 0L)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    // ── Recoveries bordereau ────────────────────────────────────────────────

    public Flux<RecoveriesBordereauRow> recoveriesRows(UUID reinsurerId, UUID treatyId,
                                                       LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT rc.id                                              AS recovery_id,
                       rc.cession_id,
                       c.treaty_id,
                       t.treaty_ref,
                       r.id                                               AS reinsurer_id,
                       r.name                                             AS reinsurer_name,
                       tp.share_pct,
                       rc.status,
                       c.source_event_id,
                       c.occurred_at,
                       rc.expected_amount                                 AS native_expected,
                       rc.received_amount                                 AS native_received,
                       (rc.expected_amount * tp.share_pct / 100)          AS participant_expected,
                       (COALESCE(rc.received_amount, 0)
                            * tp.share_pct / 100)                         AS participant_received,
                       rc.currency_code,
                       rc.invoiced_at,
                       rc.received_at,
                       rc.write_off_reason,
                       rc.created_at
                  FROM recovery rc
                  JOIN cession c             ON c.id = rc.cession_id
                  JOIN treaty t              ON t.id = c.treaty_id
                  JOIN treaty_participant tp ON tp.treaty_id = t.id
                  JOIN reinsurer r           ON r.id = tp.reinsurer_id
                 WHERE c.occurred_at >= :periodStart
                   AND c.occurred_at <  :periodEnd
                   AND (:reinsurerId::uuid IS NULL OR r.id = :reinsurerId::uuid)
                   AND (:treatyId::uuid    IS NULL OR t.id = :treatyId::uuid)
                 ORDER BY t.treaty_ref, c.occurred_at, r.name
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .bind("periodEnd",   periodEnd.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .bind("reinsurerId", reinsurerId != null ? reinsurerId : Parameters.in(UUID.class))
                .bind("treatyId",    treatyId    != null ? treatyId    : Parameters.in(UUID.class))
                .map((row, meta) -> new RecoveriesBordereauRow(
                        row.get("recovery_id", UUID.class),
                        row.get("cession_id", UUID.class),
                        row.get("treaty_id", UUID.class),
                        row.get("treaty_ref", String.class),
                        row.get("reinsurer_id", UUID.class),
                        row.get("reinsurer_name", String.class),
                        row.get("share_pct", BigDecimal.class),
                        row.get("status", String.class),
                        row.get("source_event_id", UUID.class),
                        row.get("occurred_at", OffsetDateTime.class),
                        row.get("native_expected", BigDecimal.class),
                        row.get("native_received", BigDecimal.class),
                        row.get("participant_expected", BigDecimal.class),
                        row.get("participant_received", BigDecimal.class),
                        row.get("currency_code", String.class),
                        row.get("invoiced_at", OffsetDateTime.class),
                        row.get("received_at", OffsetDateTime.class),
                        row.get("write_off_reason", String.class),
                        row.get("created_at", OffsetDateTime.class),
                        false))
                .all();
    }

    public Mono<Map<String, PerCurrencyTotal>> recoveriesPerCurrencyTotals(
            UUID reinsurerId, UUID treatyId, LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT rc.currency_code                                       AS currency_code,
                       SUM(rc.expected_amount * tp.share_pct / 100)           AS total_amount,
                       COUNT(*)                                               AS row_count
                  FROM recovery rc
                  JOIN cession c             ON c.id = rc.cession_id
                  JOIN treaty t              ON t.id = c.treaty_id
                  JOIN treaty_participant tp ON tp.treaty_id = t.id
                  JOIN reinsurer r           ON r.id = tp.reinsurer_id
                 WHERE c.occurred_at >= :periodStart
                   AND c.occurred_at <  :periodEnd
                   AND (:reinsurerId::uuid IS NULL OR r.id = :reinsurerId::uuid)
                   AND (:treatyId::uuid    IS NULL OR t.id = :treatyId::uuid)
                 GROUP BY rc.currency_code
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .bind("periodEnd",   periodEnd.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .bind("reinsurerId", reinsurerId != null ? reinsurerId : Parameters.in(UUID.class))
                .bind("treatyId",    treatyId    != null ? treatyId    : Parameters.in(UUID.class))
                .map((row, meta) -> Map.entry(
                        row.get("currency_code", String.class),
                        new PerCurrencyTotal(
                                row.get("total_amount", BigDecimal.class) != null
                                        ? row.get("total_amount", BigDecimal.class)
                                        : BigDecimal.ZERO,
                                row.get("row_count", Long.class) != null
                                        ? row.get("row_count", Long.class)
                                        : 0L)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /**
     * Returns the cession IDs backing recoveries that are currently EXPECTED
     * within the given quarter's filter. Called post-response by the recoveries
     * export to flip status EXPECTED → INVOICED (R8) — see
     * {@code BordereauReportWorkbookService.recoveriesWorkbook}.
     */
    public Flux<UUID> expectedRecoveryCessionIds(UUID reinsurerId, UUID treatyId,
                                                 LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT DISTINCT rc.cession_id
                  FROM recovery rc
                  JOIN cession c             ON c.id = rc.cession_id
                  JOIN treaty t              ON t.id = c.treaty_id
                  JOIN treaty_participant tp ON tp.treaty_id = t.id
                  JOIN reinsurer r           ON r.id = tp.reinsurer_id
                 WHERE rc.status = 'EXPECTED'
                   AND c.occurred_at >= :periodStart
                   AND c.occurred_at <  :periodEnd
                   AND (:reinsurerId::uuid IS NULL OR r.id = :reinsurerId::uuid)
                   AND (:treatyId::uuid    IS NULL OR t.id = :treatyId::uuid)
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .bind("periodEnd",   periodEnd.atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .bind("reinsurerId", reinsurerId != null ? reinsurerId : Parameters.in(UUID.class))
                .bind("treatyId",    treatyId    != null ? treatyId    : Parameters.in(UUID.class))
                .map((row, meta) -> row.get("cession_id", UUID.class))
                .all();
    }

    // ── Treaty utilization ──────────────────────────────────────────────────

    public Flux<TreatyUtilizationRow> utilizationRows(UUID treatyId, LocalDate asOfDate) {
        LocalDate asOf = asOfDate != null ? asOfDate : LocalDate.now();
        String sql = """
                SELECT t.id                                               AS treaty_id,
                       t.treaty_ref,
                       t.treaty_type,
                       t.aggregate_limit,
                       t.aggregate_limit_currency,
                       t.inception_date,
                       t.expiry_date,
                       tl.id                                              AS layer_id,
                       tl.layer_order,
                       tl.retention,
                       tl.layer_limit,
                       tl.layer_currency,
                       c.currency_code                                    AS ceded_currency,
                       COALESCE(SUM(c.ceded_amount), 0)                   AS total_ceded_native,
                       COUNT(c.id)                                        AS cession_count
                  FROM treaty t
                  LEFT JOIN treaty_layer tl ON tl.treaty_id = t.id
                  LEFT JOIN cession c ON c.treaty_id = t.id
                                    AND (tl.id IS NULL OR c.treaty_layer_id = tl.id)
                                    AND c.status IN ('ACTIVE','CEDED')
                                    AND c.cession_type = 'LOSS'
                                    AND c.occurred_at >= t.inception_date
                                    AND c.occurred_at <  :asOfDate
                 WHERE t.id = :treatyId
                 GROUP BY t.id, t.treaty_ref, t.treaty_type, t.aggregate_limit,
                          t.aggregate_limit_currency, t.inception_date, t.expiry_date,
                          tl.id, tl.layer_order, tl.retention, tl.layer_limit,
                          tl.layer_currency, c.currency_code
                 ORDER BY tl.layer_order NULLS LAST, c.currency_code NULLS LAST
                """;
        return db.sql(sql)
                .bind("treatyId", treatyId)
                .bind("asOfDate", asOf.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .map((row, meta) -> new TreatyUtilizationRow(
                        row.get("treaty_id", UUID.class),
                        row.get("treaty_ref", String.class),
                        row.get("treaty_type", String.class),
                        row.get("aggregate_limit", BigDecimal.class),
                        row.get("aggregate_limit_currency", String.class),
                        row.get("inception_date", LocalDate.class),
                        row.get("expiry_date", LocalDate.class),
                        row.get("layer_id", UUID.class),
                        row.get("layer_order", Integer.class),
                        row.get("retention", BigDecimal.class),
                        row.get("layer_limit", BigDecimal.class),
                        row.get("layer_currency", String.class),
                        row.get("ceded_currency", String.class),
                        row.get("total_ceded_native", BigDecimal.class),
                        row.get("cession_count", Long.class)))
                .all();
    }

    public Mono<Map<String, PerCurrencyTotal>> utilizationPerCurrencyTotals(
            UUID treatyId, LocalDate asOfDate) {
        LocalDate asOf = asOfDate != null ? asOfDate : LocalDate.now();
        String sql = """
                SELECT c.currency_code                                        AS currency_code,
                       COALESCE(SUM(c.ceded_amount), 0)                       AS total_amount,
                       COUNT(*)                                               AS row_count
                  FROM cession c
                  JOIN treaty t ON t.id = c.treaty_id
                 WHERE c.status IN ('ACTIVE','CEDED')
                   AND c.cession_type = 'LOSS'
                   AND t.id = :treatyId
                   AND c.occurred_at >= t.inception_date
                   AND c.occurred_at <  :asOfDate
                 GROUP BY c.currency_code
                """;
        return db.sql(sql)
                .bind("treatyId", treatyId)
                .bind("asOfDate", asOf.plusDays(1).atStartOfDay().atOffset(java.time.ZoneOffset.UTC))
                .map((row, meta) -> Map.entry(
                        row.get("currency_code", String.class),
                        new PerCurrencyTotal(
                                row.get("total_amount", BigDecimal.class) != null
                                        ? row.get("total_amount", BigDecimal.class)
                                        : BigDecimal.ZERO,
                                row.get("row_count", Long.class) != null
                                        ? row.get("row_count", Long.class)
                                        : 0L)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }
}
