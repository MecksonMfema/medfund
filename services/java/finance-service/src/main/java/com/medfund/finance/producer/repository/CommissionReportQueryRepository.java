package com.medfund.finance.producer.repository;

import com.medfund.finance.producer.dto.ClawbackRegisterRow;
import com.medfund.finance.producer.dto.CommissionStatementRow;
import com.medfund.shared.report.PerCurrencyTotal;
import io.r2dbc.spi.Parameters;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

/**
 * Raw-SQL projections for the two Phase 11 §A Phase 4 commission reports:
 * commission statement + clawback register. Rows are native-currency (per
 * parent-plan cross-phase invariant #1); the envelope's
 * {@code perCurrency} map + best-effort FX rates carry any reporting-
 * currency conversion the client wants to render.
 *
 * <p>All predicates are optional — reinsurerId-style {@code Parameters.in}
 * bindings drop them cleanly to a wildcard match per the reinsurance
 * {@code BordereauQueryRepository} precedent.
 */
@Repository
public class CommissionReportQueryRepository {

    private final DatabaseClient db;

    public CommissionReportQueryRepository(DatabaseClient db) {
        this.db = db;
    }

    // ── Commission statement ────────────────────────────────────────────────

    public Flux<CommissionStatementRow> statementRows(
            OffsetDateTime periodStart, OffsetDateTime periodEnd, UUID producerId) {
        String sql = """
                SELECT ct.id                     AS commission_transaction_id,
                       ct.reference,
                       ct.producer_id,
                       p.producer_code,
                       p.name                    AS producer_name,
                       p.home_currency           AS producer_home_currency,
                       ct.contribution_id,
                       ct.member_id,
                       ct.insurance_line,
                       ct.rate_card_id,
                       rc.name                   AS rate_card_name,
                       ct.applied_rate_pct,
                       ct.contribution_amount,
                       ct.native_amount,
                       ct.native_currency,
                       ct.status,
                       ct.occurred_at
                  FROM commission_transaction ct
                  JOIN producer p                  ON p.id = ct.producer_id
                  LEFT JOIN commission_rate_card rc ON rc.id = ct.rate_card_id
                 WHERE ct.occurred_at >= :periodStart
                   AND ct.occurred_at <  :periodEnd
                   AND (:producerId::uuid IS NULL OR ct.producer_id = :producerId::uuid)
                 ORDER BY ct.occurred_at, p.name
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd",   periodEnd)
                .bind("producerId",  producerId != null ? producerId : Parameters.in(UUID.class))
                .map((row, meta) -> new CommissionStatementRow(
                        row.get("commission_transaction_id", UUID.class),
                        row.get("reference", String.class),
                        row.get("producer_id", UUID.class),
                        row.get("producer_code", String.class),
                        row.get("producer_name", String.class),
                        row.get("producer_home_currency", String.class),
                        row.get("contribution_id", UUID.class),
                        row.get("member_id", UUID.class),
                        row.get("insurance_line", String.class),
                        row.get("rate_card_id", UUID.class),
                        row.get("rate_card_name", String.class),
                        row.get("applied_rate_pct", BigDecimal.class),
                        row.get("contribution_amount", BigDecimal.class),
                        row.get("native_amount", BigDecimal.class),
                        row.get("native_currency", String.class),
                        row.get("status", String.class),
                        row.get("occurred_at", OffsetDateTime.class)))
                .all();
    }

    public Mono<Map<String, PerCurrencyTotal>> statementPerCurrencyTotals(
            OffsetDateTime periodStart, OffsetDateTime periodEnd, UUID producerId) {
        String sql = """
                SELECT ct.native_currency                     AS currency_code,
                       SUM(ct.native_amount)                  AS total_amount,
                       COUNT(*)                               AS row_count
                  FROM commission_transaction ct
                 WHERE ct.occurred_at >= :periodStart
                   AND ct.occurred_at <  :periodEnd
                   AND (:producerId::uuid IS NULL OR ct.producer_id = :producerId::uuid)
                 GROUP BY ct.native_currency
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd",   periodEnd)
                .bind("producerId",  producerId != null ? producerId : Parameters.in(UUID.class))
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

    // ── Clawback register ───────────────────────────────────────────────────

    public Flux<ClawbackRegisterRow> clawbackRows(
            OffsetDateTime periodStart, OffsetDateTime periodEnd,
            UUID producerId, String source) {
        String sql = """
                SELECT ce.id                     AS clawback_event_id,
                       ce.source,
                       ce.triggering_event_ref,
                       ce.member_id,
                       ce.producer_id,
                       p.producer_code,
                       p.name                    AS producer_name,
                       ce.commission_transaction_id,
                       ct.reference              AS commission_reference,
                       ce.native_amount,
                       ce.native_currency,
                       ce.reason,
                       ce.occurred_at
                  FROM clawback_event ce
                  JOIN producer p                ON p.id = ce.producer_id
                  JOIN commission_transaction ct ON ct.id = ce.commission_transaction_id
                 WHERE ce.occurred_at >= :periodStart
                   AND ce.occurred_at <  :periodEnd
                   AND (:producerId::uuid IS NULL OR ce.producer_id = :producerId::uuid)
                   AND (:source           IS NULL OR ce.source     = :source)
                 ORDER BY ce.occurred_at, p.name
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd",   periodEnd)
                .bind("producerId",  producerId != null ? producerId : Parameters.in(UUID.class))
                .bind("source",      source     != null ? source     : Parameters.in(String.class))
                .map((row, meta) -> new ClawbackRegisterRow(
                        row.get("clawback_event_id", UUID.class),
                        row.get("source", String.class),
                        row.get("triggering_event_ref", String.class),
                        row.get("member_id", UUID.class),
                        row.get("producer_id", UUID.class),
                        row.get("producer_code", String.class),
                        row.get("producer_name", String.class),
                        row.get("commission_transaction_id", UUID.class),
                        row.get("commission_reference", String.class),
                        row.get("native_amount", BigDecimal.class),
                        row.get("native_currency", String.class),
                        row.get("reason", String.class),
                        row.get("occurred_at", OffsetDateTime.class)))
                .all();
    }

    public Mono<Map<String, PerCurrencyTotal>> clawbackPerCurrencyTotals(
            OffsetDateTime periodStart, OffsetDateTime periodEnd,
            UUID producerId, String source) {
        String sql = """
                SELECT ce.native_currency                     AS currency_code,
                       SUM(ce.native_amount)                  AS total_amount,
                       COUNT(*)                               AS row_count
                  FROM clawback_event ce
                 WHERE ce.occurred_at >= :periodStart
                   AND ce.occurred_at <  :periodEnd
                   AND (:producerId::uuid IS NULL OR ce.producer_id = :producerId::uuid)
                   AND (:source           IS NULL OR ce.source     = :source)
                 GROUP BY ce.native_currency
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd",   periodEnd)
                .bind("producerId",  producerId != null ? producerId : Parameters.in(UUID.class))
                .bind("source",      source     != null ? source     : Parameters.in(String.class))
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

    // ── Helpers ─────────────────────────────────────────────────────────────

    static OffsetDateTime startOfDayUtc(LocalDate d) {
        return d.atStartOfDay().atOffset(ZoneOffset.UTC);
    }
}
