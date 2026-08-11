package com.medfund.contributions.repository;

import com.medfund.contributions.dto.ReceiptsAggregateRow;
import com.medfund.contributions.dto.ReceiptsDetailResponse;
import com.medfund.contributions.dto.ReceiptsSummaryRow;
import com.medfund.shared.report.MonthlyAggregateRow;
import com.medfund.shared.report.PerCurrencyTotal;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side aggregation for the Phase 3 receipts-report family. Every
 * SUM happens inside PostgreSQL — no per-row materialisation into memory
 * before summing. Every aggregate is dimensioned by {@code currency_code}
 * so cross-currency addition never happens; the envelope layer carries
 * per-currency totals + best-effort FX rates alongside.
 *
 * <p><b>Receipt definition</b> (G30 / F25): a receipt is any completed
 * money-flow transaction — {@code PAYMENT}, {@code COPAYMENT_RECEIPT},
 * {@code CTC_OFFSET}, or their reversals ({@code REFUND},
 * {@code PAYMENT_REVERSAL}, {@code CTC_OFFSET_REVERSAL}). Amounts store
 * positive; {@code transaction_types.sign} carries the ledger polarity —
 * {@code '-'} decreases what the payer owes us (money in), {@code '+'}
 * increases it (money out or reversal). Net-received uses
 * {@code SUM(CASE tt.sign WHEN '-' THEN amount ELSE -amount END)}.
 *
 * <p><b>Attribution</b> (G33): {@code contribution_id} back-link when
 * present → its scheme; else member-owned rows attribute to the member's
 * current {@code scheme_id}; else group-owned rows without a back-link
 * land in the synthetic "Unallocated group payments" bucket (represented
 * as a NULL {@code attributed_scheme_id}).
 */
@Repository
@RequiredArgsConstructor
public class ReceiptsReportQueryRepository {

    private final DatabaseClient db;

    /** Common WHERE clause + CTE reused by every dimension. */
    private static final String RECEIPTS_CTE = """
            WITH receipts AS (
                SELECT t.id                                                 AS id,
                       t.transaction_number                                 AS transaction_number,
                       t.transaction_date                                   AS transaction_date,
                       t.transaction_type                                   AS transaction_type,
                       t.payment_method                                     AS payment_method,
                       t.reference                                          AS reference,
                       t.amount                                             AS amount,
                       t.currency_code                                      AS currency_code,
                       t.group_id                                           AS group_id,
                       t.member_id                                          AS member_id,
                       t.contribution_id                                    AS contribution_id,
                       tt.sign                                              AS sign,
                       COALESCE(c.scheme_id, m.scheme_id)                   AS attributed_scheme_id
                  FROM transactions t
                  JOIN transaction_types tt ON tt.code = t.transaction_type
                  LEFT JOIN contributions c ON c.id = t.contribution_id
                  LEFT JOIN members m       ON m.id = t.member_id
                 WHERE tt.code IN ('PAYMENT','COPAYMENT_RECEIPT','CTC_OFFSET',
                                    'REFUND','PAYMENT_REVERSAL','CTC_OFFSET_REVERSAL')
                   AND t.status = 'completed'
                   AND t.transaction_date >= :periodStart
                   AND t.transaction_date <  (:periodEnd::date + INTERVAL '1 day')
            )
            """;

    // ══════════════════════════════════════════════════════════════════════════
    //   Per-scheme
    // ══════════════════════════════════════════════════════════════════════════

    public Flux<ReceiptsSummaryRow> perSchemeSummary(LocalDate periodStart, LocalDate periodEnd) {
        String sql = RECEIPTS_CTE + """
                SELECT r.attributed_scheme_id                                        AS dimension_id,
                       COALESCE(s.name, 'Unallocated group payments')                AS dimension_name,
                       r.currency_code                                               AS currency_code,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END)    AS total_received,
                       COUNT(*)                                                      AS transaction_count
                  FROM receipts r
                  LEFT JOIN schemes s ON s.id = r.attributed_scheme_id
                 GROUP BY r.attributed_scheme_id, s.name, r.currency_code
                 ORDER BY (r.attributed_scheme_id IS NULL), s.name NULLS LAST, r.currency_code
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toSummaryRow)
                .all();
    }

    public Mono<Map<String, PerCurrencyTotal>> perSchemePerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd) {
        String sql = RECEIPTS_CTE + """
                SELECT r.currency_code                                            AS currency_code,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_amount,
                       COUNT(*)                                                   AS row_count
                  FROM receipts r
                 GROUP BY r.currency_code
                """;
        return perCurrencyTotals(sql, periodStart, periodEnd);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   Per-group
    // ══════════════════════════════════════════════════════════════════════════

    public Flux<ReceiptsSummaryRow> perGroupSummary(LocalDate periodStart, LocalDate periodEnd) {
        String sql = RECEIPTS_CTE + """
                SELECT g.id                                                       AS dimension_id,
                       COALESCE(g.name, 'Ungrouped')                              AS dimension_name,
                       r.currency_code                                            AS currency_code,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_received,
                       COUNT(*)                                                   AS transaction_count
                  FROM receipts r
                  LEFT JOIN groups g ON g.id = r.group_id
                 WHERE r.group_id IS NOT NULL
                 GROUP BY g.id, g.name, r.currency_code
                 ORDER BY g.name NULLS LAST, r.currency_code
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toSummaryRow)
                .all();
    }

    public Mono<Map<String, PerCurrencyTotal>> perGroupPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd) {
        String sql = RECEIPTS_CTE + """
                SELECT r.currency_code                                            AS currency_code,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_amount,
                       COUNT(*)                                                   AS row_count
                  FROM receipts r
                 WHERE r.group_id IS NOT NULL
                 GROUP BY r.currency_code
                """;
        return perCurrencyTotals(sql, periodStart, periodEnd);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   Per-member (paginated + searchable, G36)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Paginated per-member receipts. {@code search} matches member number
     * OR first/last name (case-insensitive substring); {@code insuranceLine}
     * filters to members whose current scheme is in that line;
     * {@code schemeId} is a hard filter on the member's current scheme.
     */
    public Flux<ReceiptsSummaryRow> perMemberSummary(LocalDate periodStart, LocalDate periodEnd,
                                                     String search, String insuranceLine, UUID schemeId,
                                                     int offset, int limit) {
        String sql = RECEIPTS_CTE + """
                SELECT m.id                                                       AS dimension_id,
                       m.member_number                                            AS member_number,
                       TRIM(m.first_name || ' ' || m.last_name)                   AS dimension_name,
                       COALESCE(s.insurance_line, 'HEALTH')                       AS insurance_line,
                       r.currency_code                                            AS currency_code,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_received,
                       COUNT(*)                                                   AS transaction_count
                  FROM receipts r
                  JOIN members m ON m.id = r.member_id
                  LEFT JOIN schemes s ON s.id = m.scheme_id
                 WHERE r.member_id IS NOT NULL
                   AND (:search IS NULL
                        OR LOWER(m.first_name)  LIKE LOWER(CONCAT('%', :search, '%'))
                        OR LOWER(m.last_name)   LIKE LOWER(CONCAT('%', :search, '%'))
                        OR         m.member_number LIKE CONCAT('%', :search, '%'))
                   AND (:insuranceLine IS NULL OR s.insurance_line = :insuranceLine)
                   AND (:schemeId::uuid IS NULL OR m.scheme_id = :schemeId::uuid)
                 GROUP BY m.id, m.member_number, m.first_name, m.last_name,
                          s.insurance_line, r.currency_code
                 ORDER BY m.last_name, m.first_name, r.currency_code
                 OFFSET :offset LIMIT :limit
                """;
        return bindMemberFilters(db.sql(sql), periodStart, periodEnd, search, insuranceLine, schemeId)
                .bind("offset", offset)
                .bind("limit",  limit)
                .map(this::toMemberSummaryRow)
                .all();
    }

    /** Count of DISTINCT (member, currency) rows for the paginated total. */
    public Mono<Long> perMemberSummaryCount(LocalDate periodStart, LocalDate periodEnd,
                                            String search, String insuranceLine, UUID schemeId) {
        String sql = RECEIPTS_CTE + """
                SELECT COUNT(*) AS total FROM (
                    SELECT m.id, r.currency_code
                      FROM receipts r
                      JOIN members m ON m.id = r.member_id
                      LEFT JOIN schemes s ON s.id = m.scheme_id
                     WHERE r.member_id IS NOT NULL
                       AND (:search IS NULL
                            OR LOWER(m.first_name)  LIKE LOWER(CONCAT('%', :search, '%'))
                            OR LOWER(m.last_name)   LIKE LOWER(CONCAT('%', :search, '%'))
                            OR         m.member_number LIKE CONCAT('%', :search, '%'))
                       AND (:insuranceLine IS NULL OR s.insurance_line = :insuranceLine)
                       AND (:schemeId::uuid IS NULL OR m.scheme_id = :schemeId::uuid)
                     GROUP BY m.id, r.currency_code
                ) x
                """;
        return bindMemberFilters(db.sql(sql), periodStart, periodEnd, search, insuranceLine, schemeId)
                .map((row, meta) -> {
                    Long total = row.get("total", Long.class);
                    return total != null ? total : 0L;
                })
                .one();
    }

    public Mono<Map<String, PerCurrencyTotal>> perMemberPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd,
                                                                          String search, String insuranceLine, UUID schemeId) {
        String sql = RECEIPTS_CTE + """
                SELECT r.currency_code                                            AS currency_code,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_amount,
                       COUNT(*)                                                   AS row_count
                  FROM receipts r
                  JOIN members m ON m.id = r.member_id
                  LEFT JOIN schemes s ON s.id = m.scheme_id
                 WHERE r.member_id IS NOT NULL
                   AND (:search IS NULL
                        OR LOWER(m.first_name)  LIKE LOWER(CONCAT('%', :search, '%'))
                        OR LOWER(m.last_name)   LIKE LOWER(CONCAT('%', :search, '%'))
                        OR         m.member_number LIKE CONCAT('%', :search, '%'))
                   AND (:insuranceLine IS NULL OR s.insurance_line = :insuranceLine)
                   AND (:schemeId::uuid IS NULL OR m.scheme_id = :schemeId::uuid)
                 GROUP BY r.currency_code
                """;
        return bindMemberFilters(db.sql(sql), periodStart, periodEnd, search, insuranceLine, schemeId)
                .map(this::toPerCurrencyEntry)
                .all()
                .filter(e -> !e.getKey().isBlank())
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   Detail (drill-down) — monthly buckets + paginated ledger, G40
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Monthly buckets for one dimension's drill-down. {@code dimensionColumn}
     * must be exactly one of {@code r.attributed_scheme_id}, {@code r.group_id},
     * {@code r.member_id} — the callers own picking the right column so
     * this method can stay generic.
     */
    public Flux<ReceiptsDetailResponse.MonthlyBucket> monthlyBuckets(String dimensionColumn, UUID dimensionId,
                                                                     LocalDate periodStart, LocalDate periodEnd) {
        assertDimensionColumn(dimensionColumn);
        String sql = RECEIPTS_CTE + """
                SELECT date_trunc('month', r.transaction_date)::date              AS month,
                       r.currency_code                                            AS currency_code,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_received,
                       COUNT(*)                                                   AS transaction_count
                  FROM receipts r
                 WHERE %s = :dimensionId
                 GROUP BY date_trunc('month', r.transaction_date), r.currency_code
                 ORDER BY 1, r.currency_code
                """.formatted(dimensionColumn);
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .bind("dimensionId", dimensionId)
                .map(this::toMonthlyBucket)
                .all();
    }

    /** Monthly buckets for the "unallocated" bucket — scheme_id IS NULL. */
    public Flux<ReceiptsDetailResponse.MonthlyBucket> monthlyBucketsUnallocated(LocalDate periodStart, LocalDate periodEnd) {
        String sql = RECEIPTS_CTE + """
                SELECT date_trunc('month', r.transaction_date)::date              AS month,
                       r.currency_code                                            AS currency_code,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_received,
                       COUNT(*)                                                   AS transaction_count
                  FROM receipts r
                 WHERE r.attributed_scheme_id IS NULL AND r.group_id IS NOT NULL
                 GROUP BY date_trunc('month', r.transaction_date), r.currency_code
                 ORDER BY 1, r.currency_code
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toMonthlyBucket)
                .all();
    }

    public Flux<ReceiptsDetailResponse.TransactionLedgerRow> ledger(String dimensionColumn, UUID dimensionId,
                                                                    LocalDate periodStart, LocalDate periodEnd,
                                                                    String transactionType, String currencyCode,
                                                                    int offset, int limit) {
        assertDimensionColumn(dimensionColumn);
        String sql = RECEIPTS_CTE + """
                SELECT r.id, r.transaction_number, r.transaction_date, r.transaction_type,
                       r.payment_method, r.reference, r.amount, r.currency_code
                  FROM receipts r
                 WHERE %s = :dimensionId
                   AND (:txnType IS NULL OR r.transaction_type = :txnType)
                   AND (:currency IS NULL OR r.currency_code = :currency)
                 ORDER BY r.transaction_date DESC, r.id DESC
                 OFFSET :offset LIMIT :limit
                """.formatted(dimensionColumn);
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .bind("dimensionId", dimensionId)
                .bind("txnType",  transactionType != null ? transactionType : null)
                .bind("currency", currencyCode != null ? currencyCode : null)
                .bind("offset", offset)
                .bind("limit",  limit)
                .map(this::toLedgerRow)
                .all();
    }

    public Mono<Long> ledgerCount(String dimensionColumn, UUID dimensionId,
                                  LocalDate periodStart, LocalDate periodEnd,
                                  String transactionType, String currencyCode) {
        assertDimensionColumn(dimensionColumn);
        String sql = RECEIPTS_CTE + """
                SELECT COUNT(*) AS total
                  FROM receipts r
                 WHERE %s = :dimensionId
                   AND (:txnType IS NULL OR r.transaction_type = :txnType)
                   AND (:currency IS NULL OR r.currency_code = :currency)
                """.formatted(dimensionColumn);
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .bind("dimensionId", dimensionId)
                .bind("txnType",  transactionType != null ? transactionType : null)
                .bind("currency", currencyCode != null ? currencyCode : null)
                .map((row, meta) -> {
                    Long total = row.get("total", Long.class);
                    return total != null ? total : 0L;
                })
                .one();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   Cross-service aggregates
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Narrow (dimension, currency, total-received) rows for Phase 5 loss-ratio.
     * {@code dimension} = {@code "SCHEME"} — matches the shape of
     * {@link com.medfund.contributions.dto.BillingAggregateRow}.
     */
    public Flux<ReceiptsAggregateRow> aggregatePerScheme(LocalDate periodStart, LocalDate periodEnd) {
        String sql = RECEIPTS_CTE + """
                SELECT r.attributed_scheme_id                                     AS dimension_id,
                       COALESCE(s.name, 'Unallocated group payments')             AS dimension_name,
                       r.currency_code                                            AS currency_code,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_received
                  FROM receipts r
                  LEFT JOIN schemes s ON s.id = r.attributed_scheme_id
                 GROUP BY r.attributed_scheme_id, s.name, r.currency_code
                 ORDER BY (r.attributed_scheme_id IS NULL), s.name NULLS LAST, r.currency_code
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(row -> new ReceiptsAggregateRow(
                        "SCHEME",
                        row.get("dimension_id", UUID.class),
                        nullSafe(row.get("dimension_name", String.class)),
                        nullSafe(row.get("currency_code", String.class)),
                        bigOrZero(row, "total_received")))
                .all();
    }

    /**
     * Monthly-bucketed aggregate for the collection-rate report + Phase 8
     * cash-flow forecast. {@code dimension} can be {@code SCHEME},
     * {@code GROUP}, or {@code MEMBER}.
     */
    public Flux<MonthlyAggregateRow> aggregateMonthly(String dimension, LocalDate periodStart, LocalDate periodEnd) {
        return switch (dimension.toUpperCase()) {
            case "SCHEME" -> aggregateMonthlyScheme(periodStart, periodEnd);
            case "GROUP"  -> aggregateMonthlyGroup(periodStart, periodEnd);
            case "MEMBER" -> aggregateMonthlyMember(periodStart, periodEnd);
            default -> Flux.error(new IllegalArgumentException(
                    "dimension must be SCHEME|GROUP|MEMBER, got '" + dimension + "'"));
        };
    }

    private Flux<MonthlyAggregateRow> aggregateMonthlyScheme(LocalDate periodStart, LocalDate periodEnd) {
        String sql = RECEIPTS_CTE + """
                SELECT r.attributed_scheme_id                                     AS dimension_id,
                       COALESCE(s.name, 'Unallocated group payments')             AS dimension_name,
                       r.currency_code                                            AS currency_code,
                       date_trunc('month', r.transaction_date)::date              AS month,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_amount
                  FROM receipts r
                  LEFT JOIN schemes s ON s.id = r.attributed_scheme_id
                 GROUP BY r.attributed_scheme_id, s.name, r.currency_code,
                          date_trunc('month', r.transaction_date)
                 ORDER BY month, s.name NULLS LAST, r.currency_code
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toMonthlyAggregate)
                .all()
                .map(row -> new MonthlyAggregateRow("SCHEME",
                        row.dimensionId(), row.dimensionName(),
                        row.currencyCode(), row.month(), row.totalAmount()));
    }

    private Flux<MonthlyAggregateRow> aggregateMonthlyGroup(LocalDate periodStart, LocalDate periodEnd) {
        String sql = RECEIPTS_CTE + """
                SELECT g.id                                                       AS dimension_id,
                       COALESCE(g.name, 'Ungrouped')                              AS dimension_name,
                       r.currency_code                                            AS currency_code,
                       date_trunc('month', r.transaction_date)::date              AS month,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_amount
                  FROM receipts r
                  LEFT JOIN groups g ON g.id = r.group_id
                 WHERE r.group_id IS NOT NULL
                 GROUP BY g.id, g.name, r.currency_code,
                          date_trunc('month', r.transaction_date)
                 ORDER BY month, g.name NULLS LAST, r.currency_code
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toMonthlyAggregate)
                .all()
                .map(row -> new MonthlyAggregateRow("GROUP",
                        row.dimensionId(), row.dimensionName(),
                        row.currencyCode(), row.month(), row.totalAmount()));
    }

    private Flux<MonthlyAggregateRow> aggregateMonthlyMember(LocalDate periodStart, LocalDate periodEnd) {
        String sql = RECEIPTS_CTE + """
                SELECT m.id                                                       AS dimension_id,
                       TRIM(m.first_name || ' ' || m.last_name)                   AS dimension_name,
                       r.currency_code                                            AS currency_code,
                       date_trunc('month', r.transaction_date)::date              AS month,
                       SUM(CASE r.sign WHEN '-' THEN r.amount ELSE -r.amount END) AS total_amount
                  FROM receipts r
                  JOIN members m ON m.id = r.member_id
                 WHERE r.member_id IS NOT NULL
                 GROUP BY m.id, m.first_name, m.last_name, r.currency_code,
                          date_trunc('month', r.transaction_date)
                 ORDER BY month, m.last_name, m.first_name, r.currency_code
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toMonthlyAggregate)
                .all()
                .map(row -> new MonthlyAggregateRow("MEMBER",
                        row.dimensionId(), row.dimensionName(),
                        row.currencyCode(), row.month(), row.totalAmount()));
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   Internal helpers
    // ══════════════════════════════════════════════════════════════════════════

    private Mono<Map<String, PerCurrencyTotal>> perCurrencyTotals(String sql,
                                                                   LocalDate periodStart, LocalDate periodEnd) {
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toPerCurrencyEntry)
                .all()
                .filter(e -> !e.getKey().isBlank())
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private DatabaseClient.GenericExecuteSpec bindMemberFilters(DatabaseClient.GenericExecuteSpec spec,
                                                                LocalDate periodStart, LocalDate periodEnd,
                                                                String search, String insuranceLine, UUID schemeId) {
        spec = spec.bind("periodStart", periodStart).bind("periodEnd", periodEnd);
        spec = (search != null && !search.isBlank())
                ? spec.bind("search", search.trim())
                : spec.bindNull("search", String.class);
        spec = (insuranceLine != null && !insuranceLine.isBlank())
                ? spec.bind("insuranceLine", insuranceLine)
                : spec.bindNull("insuranceLine", String.class);
        spec = schemeId != null
                ? spec.bind("schemeId", schemeId.toString())
                : spec.bindNull("schemeId", String.class);
        return spec;
    }

    private static void assertDimensionColumn(String col) {
        if (!"r.attributed_scheme_id".equals(col)
                && !"r.group_id".equals(col)
                && !"r.member_id".equals(col)) {
            throw new IllegalArgumentException("Illegal dimension column: " + col);
        }
    }

    private ReceiptsSummaryRow toSummaryRow(Readable row) {
        return new ReceiptsSummaryRow(
                row.get("dimension_id", UUID.class),
                nullSafe(row.get("dimension_name", String.class)),
                null,
                nullSafe(row.get("currency_code", String.class)),
                bigOrZero(row, "total_received"),
                longOrZero(row, "transaction_count"));
    }

    private ReceiptsSummaryRow toMemberSummaryRow(Readable row) {
        String memberNumber = row.get("member_number", String.class);
        String name = nullSafe(row.get("dimension_name", String.class));
        String display = memberNumber != null && !memberNumber.isBlank()
                ? memberNumber + " — " + name
                : name;
        return new ReceiptsSummaryRow(
                row.get("dimension_id", UUID.class),
                display,
                nullSafe(row.get("insurance_line", String.class)),
                nullSafe(row.get("currency_code", String.class)),
                bigOrZero(row, "total_received"),
                longOrZero(row, "transaction_count"));
    }

    private ReceiptsDetailResponse.MonthlyBucket toMonthlyBucket(Readable row) {
        return new ReceiptsDetailResponse.MonthlyBucket(
                row.get("month", LocalDate.class),
                nullSafe(row.get("currency_code", String.class)),
                bigOrZero(row, "total_received"),
                longOrZero(row, "transaction_count"));
    }

    private ReceiptsDetailResponse.TransactionLedgerRow toLedgerRow(Readable row) {
        return new ReceiptsDetailResponse.TransactionLedgerRow(
                row.get("id", UUID.class),
                row.get("transaction_number", String.class),
                row.get("transaction_date", Instant.class),
                nullSafe(row.get("transaction_type", String.class)),
                row.get("payment_method", String.class),
                row.get("reference", String.class),
                bigOrZero(row, "amount"),
                nullSafe(row.get("currency_code", String.class)));
    }

    private MonthlyAggregateRow toMonthlyAggregate(Readable row) {
        return new MonthlyAggregateRow(
                "",
                row.get("dimension_id", UUID.class),
                nullSafe(row.get("dimension_name", String.class)),
                nullSafe(row.get("currency_code", String.class)),
                row.get("month", LocalDate.class),
                bigOrZero(row, "total_amount"));
    }

    private Map.Entry<String, PerCurrencyTotal> toPerCurrencyEntry(Readable row) {
        String cc = row.get("currency_code", String.class);
        return Map.entry(
                cc != null ? cc : "",
                new PerCurrencyTotal(bigOrZero(row, "total_amount"), longOrZero(row, "row_count")));
    }

    private static long longOrZero(Readable row, String column) {
        Long v = row.get(column, Long.class);
        return v != null ? v : 0L;
    }

    private static BigDecimal bigOrZero(Readable row, String column) {
        BigDecimal v = row.get(column, BigDecimal.class);
        return v != null ? v : BigDecimal.ZERO;
    }

    private static String nullSafe(String s) {
        return s != null ? s : "";
    }
}
