package com.medfund.contributions.repository;

import com.medfund.contributions.dto.BillingAggregateRow;
import com.medfund.contributions.dto.BillingMonthlyBucket;
import com.medfund.contributions.dto.GroupBillingSummaryRow;
import com.medfund.contributions.dto.SchemeBillingSummaryRow;
import com.medfund.shared.report.PerCurrencyTotal;
import io.r2dbc.spi.Readable;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side aggregation for the Phase 2 billing-report family. All GROUP BY
 * happens inside PostgreSQL — no per-row materialisation into memory before
 * summing. Every aggregate is dimensioned by {@code currency_code} so cross-
 * currency addition never happens; the envelope layer carries per-currency
 * totals + best-effort FX rates alongside.
 *
 * <p>"Committed" contributions are those already rolled onto an invoice —
 * i.e. {@code invoice_id IS NOT NULL}. Preview-only rows (briefly
 * materialised during the commit workflow) are always excluded from
 * reporting numbers per the Phase 2 plan.
 *
 * <p>Age bands are computed at query time from the beneficiary's
 * {@code date_of_birth} versus the contribution's {@code period_start} —
 * a member's own line uses {@code members.date_of_birth}; a dependant
 * line uses {@code dependants.date_of_birth}. Buckets: 0-18, 19-35, 36-55,
 * 56+. Frozen historical age reproducibility is inherent because
 * period_start on the row is immutable.
 */
@Repository
@RequiredArgsConstructor
public class BillingReportQueryRepository {

    private final DatabaseClient db;

    // ── Per-scheme aggregate ────────────────────────────────────────────────

    /**
     * Per-scheme, per-currency billing aggregate for the reporting window.
     * Only contributions that have landed on an invoice count — plan
     * "billing report" = billed amount, not preview.
     */
    public Flux<SchemeBillingSummaryRow> perSchemeSummary(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                WITH billed AS (
                    SELECT c.scheme_id,
                           c.currency_code,
                           c.member_id,
                           c.dependant_id,
                           c.amount,
                           c.status,
                           EXTRACT(YEAR FROM AGE(c.period_start,
                               COALESCE(d.date_of_birth, m.date_of_birth)))::int AS age_at_period
                      FROM contributions c
                      LEFT JOIN members    m ON m.id = c.member_id
                      LEFT JOIN dependants d ON d.id = c.dependant_id
                     WHERE c.invoice_id IS NOT NULL
                       AND c.period_start >= :periodStart
                       AND c.period_start <= :periodEnd
                )
                SELECT s.id                                                                     AS scheme_id,
                       COALESCE(s.name, '')                                                     AS scheme_name,
                       COALESCE(s.insurance_line, 'HEALTH')                                     AS insurance_line,
                       b.currency_code                                                          AS currency_code,
                       COUNT(DISTINCT b.member_id)                                              AS principal_count,
                       COUNT(DISTINCT b.dependant_id) FILTER (WHERE b.dependant_id IS NOT NULL) AS dependant_count,
                       COUNT(DISTINCT COALESCE(b.dependant_id, b.member_id))                    AS lives_covered,
                       COALESCE(SUM(b.amount) FILTER (WHERE b.age_at_period BETWEEN  0 AND 18), 0) AS age_0_18,
                       COALESCE(SUM(b.amount) FILTER (WHERE b.age_at_period BETWEEN 19 AND 35), 0) AS age_19_35,
                       COALESCE(SUM(b.amount) FILTER (WHERE b.age_at_period BETWEEN 36 AND 55), 0) AS age_36_55,
                       COALESCE(SUM(b.amount) FILTER (WHERE b.age_at_period >= 56),               0) AS age_56_plus,
                       COALESCE(SUM(b.amount),                                                 0) AS total_billed,
                       COALESCE(SUM(b.amount) FILTER (WHERE b.status = 'paid'),               0) AS total_paid
                  FROM billed b
                  JOIN schemes s ON s.id = b.scheme_id
                 GROUP BY s.id, s.name, s.insurance_line, b.currency_code
                 ORDER BY s.name ASC NULLS LAST, b.currency_code ASC
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toSchemeRow)
                .all();
    }

    /**
     * Filtered-set per-currency totals for the per-scheme report — feeds the
     * envelope's {@code perCurrency} map (G18). Same WHERE clause as the
     * aggregate itself.
     */
    public Mono<Map<String, PerCurrencyTotal>> perSchemePerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd) {
        return db.sql("""
                SELECT c.currency_code                             AS currency_code,
                       COALESCE(SUM(c.amount), 0)                  AS total_amount,
                       COUNT(*)                                    AS row_count
                  FROM contributions c
                 WHERE c.invoice_id IS NOT NULL
                   AND c.period_start >= :periodStart
                   AND c.period_start <= :periodEnd
                 GROUP BY c.currency_code
                """)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toPerCurrencyEntry)
                .all()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    // ── Per-group aggregate ─────────────────────────────────────────────────

    /**
     * Per-group, per-currency billing aggregate. Only rows with a
     * {@code group_id} count (a member paying individually is not a group
     * billing row). Same {@code invoice_id IS NOT NULL} filter — no preview
     * rows in employer numbers.
     */
    public Flux<GroupBillingSummaryRow> perGroupSummary(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT g.id                                                                           AS group_id,
                       COALESCE(g.name, '')                                                           AS group_name,
                       c.currency_code                                                                AS currency_code,
                       COUNT(DISTINCT c.member_id)                                                    AS principal_count,
                       COUNT(DISTINCT c.dependant_id) FILTER (WHERE c.dependant_id IS NOT NULL)       AS dependant_count,
                       COUNT(DISTINCT COALESCE(c.dependant_id, c.member_id))                          AS lives_covered,
                       COALESCE(SUM(c.amount), 0)                                                     AS total_billed,
                       COALESCE(SUM(c.amount) FILTER (WHERE c.status = 'paid'), 0)                    AS total_paid
                  FROM contributions c
                  JOIN groups g ON g.id = c.group_id
                 WHERE c.invoice_id IS NOT NULL
                   AND c.group_id IS NOT NULL
                   AND c.period_start >= :periodStart
                   AND c.period_start <= :periodEnd
                 GROUP BY g.id, g.name, c.currency_code
                 ORDER BY g.name ASC NULLS LAST, c.currency_code ASC
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toGroupRow)
                .all();
    }

    public Mono<Map<String, PerCurrencyTotal>> perGroupPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd) {
        return db.sql("""
                SELECT c.currency_code                             AS currency_code,
                       COALESCE(SUM(c.amount), 0)                  AS total_amount,
                       COUNT(*)                                    AS row_count
                  FROM contributions c
                 WHERE c.invoice_id IS NOT NULL
                   AND c.group_id IS NOT NULL
                   AND c.period_start >= :periodStart
                   AND c.period_start <= :periodEnd
                 GROUP BY c.currency_code
                """)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toPerCurrencyEntry)
                .all()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    // ── Scheme detail (one scheme, monthly breakdown) ───────────────────────

    public Flux<SchemeBillingSummaryRow> schemeSummary(UUID schemeId, LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                WITH billed AS (
                    SELECT c.scheme_id,
                           c.currency_code,
                           c.member_id,
                           c.dependant_id,
                           c.amount,
                           c.status,
                           EXTRACT(YEAR FROM AGE(c.period_start,
                               COALESCE(d.date_of_birth, m.date_of_birth)))::int AS age_at_period
                      FROM contributions c
                      LEFT JOIN members    m ON m.id = c.member_id
                      LEFT JOIN dependants d ON d.id = c.dependant_id
                     WHERE c.scheme_id  = :schemeId
                       AND c.invoice_id IS NOT NULL
                       AND c.period_start >= :periodStart
                       AND c.period_start <= :periodEnd
                )
                SELECT s.id                                                                     AS scheme_id,
                       COALESCE(s.name, '')                                                     AS scheme_name,
                       COALESCE(s.insurance_line, 'HEALTH')                                     AS insurance_line,
                       b.currency_code                                                          AS currency_code,
                       COUNT(DISTINCT b.member_id)                                              AS principal_count,
                       COUNT(DISTINCT b.dependant_id) FILTER (WHERE b.dependant_id IS NOT NULL) AS dependant_count,
                       COUNT(DISTINCT COALESCE(b.dependant_id, b.member_id))                    AS lives_covered,
                       COALESCE(SUM(b.amount) FILTER (WHERE b.age_at_period BETWEEN  0 AND 18), 0) AS age_0_18,
                       COALESCE(SUM(b.amount) FILTER (WHERE b.age_at_period BETWEEN 19 AND 35), 0) AS age_19_35,
                       COALESCE(SUM(b.amount) FILTER (WHERE b.age_at_period BETWEEN 36 AND 55), 0) AS age_36_55,
                       COALESCE(SUM(b.amount) FILTER (WHERE b.age_at_period >= 56),               0) AS age_56_plus,
                       COALESCE(SUM(b.amount),                                                 0) AS total_billed,
                       COALESCE(SUM(b.amount) FILTER (WHERE b.status = 'paid'),               0) AS total_paid
                  FROM billed b
                  JOIN schemes s ON s.id = b.scheme_id
                 GROUP BY s.id, s.name, s.insurance_line, b.currency_code
                 ORDER BY b.currency_code ASC
                """;
        return db.sql(sql)
                .bind("schemeId", schemeId)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toSchemeRow)
                .all();
    }

    public Flux<BillingMonthlyBucket> schemeMonthly(UUID schemeId, LocalDate periodStart, LocalDate periodEnd) {
        return monthly("c.scheme_id = :schemeId", schemeId, periodStart, periodEnd);
    }

    // ── Group detail ────────────────────────────────────────────────────────

    public Flux<GroupBillingSummaryRow> groupSummary(UUID groupId, LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT g.id                                                                           AS group_id,
                       COALESCE(g.name, '')                                                           AS group_name,
                       c.currency_code                                                                AS currency_code,
                       COUNT(DISTINCT c.member_id)                                                    AS principal_count,
                       COUNT(DISTINCT c.dependant_id) FILTER (WHERE c.dependant_id IS NOT NULL)       AS dependant_count,
                       COUNT(DISTINCT COALESCE(c.dependant_id, c.member_id))                          AS lives_covered,
                       COALESCE(SUM(c.amount), 0)                                                     AS total_billed,
                       COALESCE(SUM(c.amount) FILTER (WHERE c.status = 'paid'), 0)                    AS total_paid
                  FROM contributions c
                  JOIN groups g ON g.id = c.group_id
                 WHERE c.group_id   = :groupId
                   AND c.invoice_id IS NOT NULL
                   AND c.period_start >= :periodStart
                   AND c.period_start <= :periodEnd
                 GROUP BY g.id, g.name, c.currency_code
                 ORDER BY c.currency_code ASC
                """;
        return db.sql(sql)
                .bind("groupId", groupId)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toGroupRow)
                .all();
    }

    public Flux<BillingMonthlyBucket> groupMonthly(UUID groupId, LocalDate periodStart, LocalDate periodEnd) {
        return monthly("c.group_id = :groupId", groupId, periodStart, periodEnd);
    }

    // ── Cross-service aggregate ─────────────────────────────────────────────

    /**
     * Narrow (scheme, currency, total-billed) rows for the cross-service
     * consumers (Phase 3 collection-rate, Phase 5 loss-ratio). Same filter
     * as the summary — invoiced contributions only.
     */
    public Flux<BillingAggregateRow> aggregatePerScheme(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT s.id                              AS scheme_id,
                       COALESCE(s.name, '')              AS scheme_name,
                       c.currency_code                   AS currency_code,
                       COALESCE(SUM(c.amount), 0)        AS total_billed
                  FROM contributions c
                  JOIN schemes s ON s.id = c.scheme_id
                 WHERE c.invoice_id IS NOT NULL
                   AND c.period_start >= :periodStart
                   AND c.period_start <= :periodEnd
                 GROUP BY s.id, s.name, c.currency_code
                 ORDER BY s.name ASC NULLS LAST, c.currency_code ASC
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toAggregateRow)
                .all();
    }

    // ── Internal helpers ────────────────────────────────────────────────────

    private Flux<BillingMonthlyBucket> monthly(String scopeClause, UUID id,
                                               LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT date_trunc('month', c.period_start)::date              AS month_start,
                       c.currency_code                                        AS currency_code,
                       COUNT(DISTINCT c.member_id)                            AS principal_count,
                       COUNT(DISTINCT c.dependant_id)
                           FILTER (WHERE c.dependant_id IS NOT NULL)          AS dependant_count,
                       COALESCE(SUM(c.amount), 0)                             AS total_billed,
                       COALESCE(SUM(c.amount) FILTER (WHERE c.status='paid'),
                                0)                                            AS total_paid
                  FROM contributions c
                 WHERE %s
                   AND c.invoice_id IS NOT NULL
                   AND c.period_start >= :periodStart
                   AND c.period_start <= :periodEnd
                 GROUP BY date_trunc('month', c.period_start), c.currency_code
                 ORDER BY 1 ASC, c.currency_code ASC
                """.formatted(scopeClause);
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .bind(scopeClause.contains(":schemeId") ? "schemeId" : "groupId", id)
                .map(this::toMonthlyBucket)
                .all();
    }

    private SchemeBillingSummaryRow toSchemeRow(Readable row) {
        return new SchemeBillingSummaryRow(
                row.get("scheme_id", UUID.class),
                nullSafe(row.get("scheme_name", String.class)),
                nullSafe(row.get("insurance_line", String.class)),
                nullSafe(row.get("currency_code", String.class)),
                longOrZero(row, "principal_count"),
                longOrZero(row, "dependant_count"),
                longOrZero(row, "lives_covered"),
                bigOrZero(row, "age_0_18"),
                bigOrZero(row, "age_19_35"),
                bigOrZero(row, "age_36_55"),
                bigOrZero(row, "age_56_plus"),
                bigOrZero(row, "total_billed"),
                bigOrZero(row, "total_paid"));
    }

    private GroupBillingSummaryRow toGroupRow(Readable row) {
        return new GroupBillingSummaryRow(
                row.get("group_id", UUID.class),
                nullSafe(row.get("group_name", String.class)),
                nullSafe(row.get("currency_code", String.class)),
                longOrZero(row, "principal_count"),
                longOrZero(row, "dependant_count"),
                longOrZero(row, "lives_covered"),
                bigOrZero(row, "total_billed"),
                bigOrZero(row, "total_paid"));
    }

    private BillingMonthlyBucket toMonthlyBucket(Readable row) {
        return new BillingMonthlyBucket(
                row.get("month_start", LocalDate.class),
                nullSafe(row.get("currency_code", String.class)),
                longOrZero(row, "principal_count"),
                longOrZero(row, "dependant_count"),
                bigOrZero(row, "total_billed"),
                bigOrZero(row, "total_paid"));
    }

    private BillingAggregateRow toAggregateRow(Readable row) {
        return new BillingAggregateRow(
                row.get("scheme_id", UUID.class),
                nullSafe(row.get("scheme_name", String.class)),
                nullSafe(row.get("currency_code", String.class)),
                bigOrZero(row, "total_billed"));
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
