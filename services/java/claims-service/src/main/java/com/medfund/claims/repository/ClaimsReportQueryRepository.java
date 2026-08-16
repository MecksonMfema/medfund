package com.medfund.claims.repository;

import com.medfund.claims.dto.ClaimStatusMatrixCell;
import com.medfund.claims.dto.ClaimsAggregateRow;
import com.medfund.claims.dto.ClaimsDetailResponse;
import com.medfund.claims.dto.ClaimsSummaryRow;
import com.medfund.claims.dto.DenialAnalysisResponse;
import com.medfund.claims.dto.FrequencySeverityRow;
import com.medfund.claims.dto.HighCostClaimantRow;
import com.medfund.claims.dto.PreAuthActivityResponse;
import com.medfund.claims.dto.PreAuthActivityRow;
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
 * Server-side aggregation for the Phase 4 claims-financial report family.
 * Every SUM happens inside PostgreSQL — no per-row materialisation into
 * memory before summing. Every aggregate is dimensioned by
 * {@code currency_code} so cross-currency addition never happens; the
 * envelope layer carries per-currency totals + best-effort FX rates
 * alongside.
 *
 * <p><b>Period clock</b> (G41): financial-exposure views
 * (CLAIMS_SUMMARY / HIGH_COST_CLAIMANT / aggregate) filter on
 * {@code adjudicated_at}; PRE_AUTH_ACTIVITY filters on
 * {@code requested_date} on {@code pre_authorizations}.
 *
 * <p><b>Funnel</b> (G42): every aggregate carries claimed / approved / paid
 * sums. {@code paid_amount} is a foreign write from finance-service's
 * payment-run flow — 0 by default until the run executes.
 *
 * <p><b>Insurance line</b>: filtered directly on {@code claims.insurance_line}
 * (V053) — the claims table carries its own column, no members join needed.
 */
@Repository
@RequiredArgsConstructor
public class ClaimsReportQueryRepository {

    private final DatabaseClient db;

    /** Funnel sums shared by every claim-dimensioned aggregate. */
    private static final String FUNNEL = """
            COALESCE(SUM(c.claimed_amount), 0)  AS total_claimed,
            COALESCE(SUM(c.approved_amount), 0) AS total_approved,
            COALESCE(SUM(c.paid_amount), 0)     AS total_paid
            """;

    private static final String CLAIMS_PERIOD = """
             c.adjudicated_at >= :periodStart
            AND c.adjudicated_at <  (:periodEnd::date + INTERVAL '1 day')
            """;

    /**
     * CLAIM_STATUS_LIST age-bucket CASE (G49) over days since submission.
     * Boundaries are hard-coded per the G49 caveat; tenant-configurable
     * bucketing is a follow-up. Shared by the matrix cells and the drill
     * WHERE so a cell always drills into the exact ledger that built it.
     */
    private static final String AGE_BUCKET = """
            CASE WHEN EXTRACT(EPOCH FROM (NOW() - c.submission_date)) / 86400 <= 3 THEN '0-3'
                 WHEN EXTRACT(EPOCH FROM (NOW() - c.submission_date)) / 86400 <= 7 THEN '4-7'
                 WHEN EXTRACT(EPOCH FROM (NOW() - c.submission_date)) / 86400 <= 14 THEN '8-14'
                 WHEN EXTRACT(EPOCH FROM (NOW() - c.submission_date)) / 86400 <= 30 THEN '15-30'
                 ELSE '>30' END
            """;

    // ══════════════════════════════════════════════════════════════════════════
    //   Per-scheme (CLAIMS_SUMMARY)
    // ══════════════════════════════════════════════════════════════════════════

    public Flux<ClaimsSummaryRow> perSchemeSummary(LocalDate periodStart, LocalDate periodEnd,
                                                   String insuranceLine) {
        String sql = """
                SELECT c.scheme_id      AS dimension_id,
                       s.name           AS dimension_name,
                       NULL             AS insurance_line,
                       c.currency_code  AS currency_code,
                       COUNT(*)         AS claim_count,
                """ + FUNNEL + """
                  FROM claims c
                  JOIN schemes s ON s.id = c.scheme_id
                 WHERE """ + CLAIMS_PERIOD + """
                   AND (:insuranceLine IS NULL OR c.insurance_line = :insuranceLine)
                 GROUP BY c.scheme_id, s.name, c.currency_code
                 ORDER BY s.name, c.currency_code
                """;
        return bindInsuranceLine(db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                        insuranceLine)
                .map(this::toSummaryRow)
                .all();
    }

    /**
     * Whole-window native totals per currency (G6) shared by the per-scheme,
     * per-provider, and cross-service aggregate envelopes. The insurance-line
     * filter, when present, mirrors the summary SQL so the totals stay the
     * filtered-set totals per G18.
     */
    public Mono<Map<String, PerCurrencyTotal>> claimsPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd,
                                                                       String insuranceLine) {
        String sql = """
                SELECT c.currency_code AS currency_code,
                       COALESCE(SUM(c.claimed_amount), 0) + COALESCE(SUM(c.approved_amount), 0)
                           + COALESCE(SUM(c.paid_amount), 0) AS total_amount,
                       COUNT(*)        AS row_count
                  FROM claims c
                 WHERE """ + CLAIMS_PERIOD + """
                   AND (:insuranceLine IS NULL OR c.insurance_line = :insuranceLine)
                 GROUP BY c.currency_code
                """;
        return bindInsuranceLine(db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                        insuranceLine)
                .map(this::toPerCurrencyEntry)
                .all()
                .filter(e -> !e.getKey().isBlank())
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   Per-provider (CLAIMS_SUMMARY)
    // ══════════════════════════════════════════════════════════════════════════

    public Flux<ClaimsSummaryRow> perProviderSummary(LocalDate periodStart, LocalDate periodEnd,
                                                     String insuranceLine) {
        String sql = """
                SELECT c.provider_id   AS dimension_id,
                       p.name          AS dimension_name,
                       NULL            AS insurance_line,
                       c.currency_code AS currency_code,
                       COUNT(*)        AS claim_count,
                """ + FUNNEL + """
                  FROM claims c
                  JOIN providers p ON p.id = c.provider_id
                 WHERE """ + CLAIMS_PERIOD + """
                   AND (:insuranceLine IS NULL OR c.insurance_line = :insuranceLine)
                 GROUP BY c.provider_id, p.name, c.currency_code
                 ORDER BY p.name, c.currency_code
                """;
        return bindInsuranceLine(db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                        insuranceLine)
                .map(this::toSummaryRow)
                .all();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   Per-group (CLAIMS_SUMMARY, §B G45)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Per-group claims aggregate — groups resolved through
     * {@code members.group_id}. Members without a group join the
     * {@code 'Ungrouped'} pseudo-row (null {@code dimension_id}) mirroring
     * the cross-service {@code aggregateGroup} precedent.
     */
    public Flux<ClaimsSummaryRow> perGroupSummary(LocalDate periodStart, LocalDate periodEnd,
                                                  String insuranceLine) {
        String sql = """
                SELECT g.id                      AS dimension_id,
                       COALESCE(g.name, 'Ungrouped') AS dimension_name,
                       NULL                       AS insurance_line,
                       c.currency_code            AS currency_code,
                       COUNT(*)                   AS claim_count,
                """ + FUNNEL + """
                  FROM claims c
                  JOIN members m ON m.id = c.member_id
                  LEFT JOIN groups g ON g.id = m.group_id
                 WHERE """ + CLAIMS_PERIOD + """
                   AND (:insuranceLine IS NULL OR c.insurance_line = :insuranceLine)
                 GROUP BY g.id, g.name, c.currency_code
                 ORDER BY g.name NULLS LAST, c.currency_code
                """;
        return bindInsuranceLine(db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                        insuranceLine)
                .map(this::toSummaryRow)
                .all();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   Per-member (CLAIMS_SUMMARY, §B G45)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Per-member claims aggregate — one row per (member, insurance line,
     * currency) so the line cross-cut renders per G45. Search is a plain
     * ILIKE over {@code member_number} / first / last name (pg_trgm is NOT
     * on the classpath — Phase 3 deviation memory). Paginated server-side;
     * the count query and the filtered-set per-currency totals reuse the
     * same WHERE so G18 totals stay the filtered set.
     */
    public Flux<ClaimsSummaryRow> perMemberSummary(LocalDate periodStart, LocalDate periodEnd,
                                                   String search, String insuranceLine,
                                                   UUID schemeId, UUID providerId,
                                                   int offset, int limit) {
        String sql = """
                SELECT c.member_id            AS dimension_id,
                       TRIM(m.first_name || ' ' || m.last_name) AS dimension_name,
                       c.insurance_line       AS insurance_line,
                       c.currency_code        AS currency_code,
                       COUNT(*)               AS claim_count,
                """ + FUNNEL + """
                  FROM claims c
                  JOIN members m ON m.id = c.member_id
                 WHERE """ + CLAIMS_PERIOD + """
                   AND (:insuranceLine IS NULL OR c.insurance_line = :insuranceLine)
                   AND (:schemeId::uuid IS NULL OR m.scheme_id = :schemeId::uuid)
                   AND (:providerId::uuid IS NULL OR c.provider_id = :providerId::uuid)
                   AND (:search IS NULL
                        OR m.member_number ILIKE '%' || :search || '%'
                        OR m.first_name    ILIKE '%' || :search || '%'
                        OR m.last_name     ILIKE '%' || :search || '%')
                 GROUP BY c.member_id, m.first_name, m.last_name, c.insurance_line, c.currency_code
                 ORDER BY m.last_name, m.first_name, c.insurance_line, c.currency_code
                 OFFSET :offset LIMIT :limit
                """;
        return bindInsuranceLine(
                        bindNullScheme(bindNullSearch(bindNullProvider(
                                db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                                providerId), search), schemeId),
                        insuranceLine)
                .bind("offset", offset)
                .bind("limit", limit)
                .map(this::toSummaryRow)
                .all();
    }

    /**
     * Paged-row count for {@link #perMemberSummary} — counts the grouped
     * rows, not raw claims, so the page metadata matches what the paged
     * query returns.
     */
    public Mono<Long> perMemberCount(LocalDate periodStart, LocalDate periodEnd,
                                     String search, String insuranceLine,
                                     UUID schemeId, UUID providerId) {
        String sql = """
                SELECT COUNT(*) AS total
                  FROM (
                    SELECT c.member_id, c.insurance_line, c.currency_code
                      FROM claims c
                      JOIN members m ON m.id = c.member_id
                     WHERE """ + CLAIMS_PERIOD + """
                       AND (:insuranceLine IS NULL OR c.insurance_line = :insuranceLine)
                       AND (:schemeId::uuid IS NULL OR m.scheme_id = :schemeId::uuid)
                       AND (:providerId::uuid IS NULL OR c.provider_id = :providerId::uuid)
                       AND (:search IS NULL
                            OR m.member_number ILIKE '%' || :search || '%'
                            OR m.first_name    ILIKE '%' || :search || '%'
                            OR m.last_name     ILIKE '%' || :search || '%')
                     GROUP BY c.member_id, m.first_name, m.last_name, c.insurance_line, c.currency_code
                  ) grouped
                """;
        return bindInsuranceLine(
                        bindNullScheme(bindNullSearch(bindNullProvider(
                                db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                                providerId), search), schemeId),
                        insuranceLine)
                .map((row, meta) -> {
                    Long total = row.get("total", Long.class);
                    return total != null ? total : 0L;
                })
                .one();
    }

    /**
     * Filtered-set native totals for the per-member report (G18) — same
     * search / scheme / provider / line filters as the paged summary so the
     * envelope's perCurrency block reconciles against the on-screen rows.
     */
    public Mono<Map<String, PerCurrencyTotal>> memberPerCurrencyTotals(LocalDate periodStart, LocalDate periodEnd,
                                                                        String search, String insuranceLine,
                                                                        UUID schemeId, UUID providerId) {
        String sql = """
                SELECT c.currency_code AS currency_code,
                       COALESCE(SUM(c.claimed_amount), 0) + COALESCE(SUM(c.approved_amount), 0)
                           + COALESCE(SUM(c.paid_amount), 0) AS total_amount,
                       COUNT(*)        AS row_count
                  FROM claims c
                  JOIN members m ON m.id = c.member_id
                 WHERE """ + CLAIMS_PERIOD + """
                   AND (:insuranceLine IS NULL OR c.insurance_line = :insuranceLine)
                   AND (:schemeId::uuid IS NULL OR m.scheme_id = :schemeId::uuid)
                   AND (:providerId::uuid IS NULL OR c.provider_id = :providerId::uuid)
                   AND (:search IS NULL
                        OR m.member_number ILIKE '%' || :search || '%'
                        OR m.first_name    ILIKE '%' || :search || '%'
                        OR m.last_name     ILIKE '%' || :search || '%')
                 GROUP BY c.currency_code
                """;
        return bindInsuranceLine(
                        bindNullScheme(bindNullSearch(bindNullProvider(
                                db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                                providerId), search), schemeId),
                        insuranceLine)
                .map(this::toPerCurrencyEntry)
                .all()
                .filter(e -> !e.getKey().isBlank())
                .collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    /**
     * Display-name lookup for a drill-down dimension (GROUP / MEMBER variants
     * land here in §B). Empty when the id resolves to nothing — the service
     * default-fills a blank name rather than failing the drill.
     */
    public Mono<String> dimensionName(String dimension, UUID id) {
        return switch (dimension.toUpperCase()) {
            case "SCHEME"   -> db.sql("SELECT name AS dimension_name FROM schemes WHERE id = :id")
                    .bind("id", id).map((row, meta) -> row.get("dimension_name", String.class)).one();
            case "PROVIDER" -> db.sql("SELECT name AS dimension_name FROM providers WHERE id = :id")
                    .bind("id", id).map((row, meta) -> row.get("dimension_name", String.class)).one();
            case "GROUP"    -> db.sql("SELECT COALESCE(name, 'Ungrouped') AS dimension_name FROM groups WHERE id = :id")
                    .bind("id", id).map((row, meta) -> row.get("dimension_name", String.class)).one();
            case "MEMBER"   -> db.sql("SELECT TRIM(first_name || ' ' || last_name) AS dimension_name FROM members WHERE id = :id")
                    .bind("id", id).map((row, meta) -> row.get("dimension_name", String.class)).one();
            default -> Mono.error(new IllegalArgumentException(
                    "dimension must be SCHEME|PROVIDER|GROUP|MEMBER, got '" + dimension + "'"));
        };
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   Detail (drill-down) — monthly buckets + paginated ledger, G40
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Monthly funnel buckets for one dimension's drill-down.
     * {@code dimensionColumn} must be exactly one of {@code c.scheme_id},
     * {@code c.provider_id}, {@code m.group_id}, {@code c.member_id} (the
     * members join is present so all four resolve — member_id is 1:1 per
     * claim, so the join never multiplies rows for the scheme/provider
     * variants).
     */
    public Flux<ClaimsDetailResponse.MonthlyBucket> monthlyBuckets(String dimensionColumn, UUID dimensionId,
                                                                    LocalDate periodStart, LocalDate periodEnd) {
        assertDimensionColumn(dimensionColumn);
        String sql = ("""
                SELECT date_trunc('month', c.adjudicated_at)::date AS month,
                       c.currency_code                              AS currency_code,
                       COUNT(*)                                     AS claim_count,
                """ + FUNNEL + """
                  FROM claims c
                  LEFT JOIN members m ON m.id = c.member_id
                 WHERE %s = :dimensionId
                   AND """ + CLAIMS_PERIOD + """
                 GROUP BY date_trunc('month', c.adjudicated_at), c.currency_code
                 ORDER BY 1, c.currency_code
                """).formatted(dimensionColumn);
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .bind("dimensionId", dimensionId)
                .map(this::toMonthlyBucket)
                .all();
    }

    public Flux<ClaimsDetailResponse.ClaimLedgerRow> ledger(String dimensionColumn, UUID dimensionId,
                                                            LocalDate periodStart, LocalDate periodEnd,
                                                            String status, UUID providerId, String currencyCode,
                                                            int offset, int limit) {
        assertDimensionColumn(dimensionColumn);
        String sql = ("""
                SELECT c.id, c.claim_number,
                       TRIM(COALESCE(m.first_name, '') || ' ' || COALESCE(m.last_name, '')) AS member_name,
                       COALESCE(p.name, '')                        AS provider_name,
                       c.submission_date, c.service_date, c.adjudicated_at,
                       c.status, c.rejection_reason AS rejection_code,
                       c.claimed_amount, c.approved_amount, c.paid_amount,
                       c.currency_code
                  FROM claims c
                  LEFT JOIN members   m ON m.id = c.member_id
                  LEFT JOIN providers p ON p.id = c.provider_id
                 WHERE %s = :dimensionId
                   AND """ + CLAIMS_PERIOD + """
                   AND (:status IS NULL OR c.status = :status)
                   AND (:providerId::uuid IS NULL OR c.provider_id = :providerId::uuid)
                   AND (:currency IS NULL OR c.currency_code = :currency)
                 ORDER BY c.adjudicated_at DESC NULLS LAST, c.id DESC
                 OFFSET :offset LIMIT :limit
                """).formatted(dimensionColumn);
        return bindNullCurrency(bindNullProvider(bindNullStatus(
                        db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd)
                                .bind("dimensionId", dimensionId),
                        status), providerId), currencyCode)
                .bind("offset", offset)
                .bind("limit", limit)
                .map(this::toLedgerRow)
                .all();
    }

    public Mono<Long> ledgerCount(String dimensionColumn, UUID dimensionId,
                                  LocalDate periodStart, LocalDate periodEnd,
                                  String status, UUID providerId, String currencyCode) {
        assertDimensionColumn(dimensionColumn);
        String sql = ("""
                SELECT COUNT(*) AS total
                  FROM claims c
                  LEFT JOIN members m ON m.id = c.member_id
                 WHERE %s = :dimensionId
                   AND """ + CLAIMS_PERIOD + """
                   AND (:status IS NULL OR c.status = :status)
                   AND (:providerId::uuid IS NULL OR c.provider_id = :providerId::uuid)
                   AND (:currency IS NULL OR c.currency_code = :currency)
                """).formatted(dimensionColumn);
        return bindNullCurrency(bindNullProvider(bindNullStatus(
                        db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd)
                                .bind("dimensionId", dimensionId),
                        status), providerId), currencyCode)
                .map((row, meta) -> {
                    Long total = row.get("total", Long.class);
                    return total != null ? total : 0L;
                })
                .one();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   Cross-service aggregate (Phase 5 loss-ratio consumer, G44)
    // ══════════════════════════════════════════════════════════════════════════

    public Flux<ClaimsAggregateRow> aggregate(String dimension, LocalDate periodStart, LocalDate periodEnd) {
        return switch (dimension.toUpperCase()) {
            case "SCHEME"   -> aggregateScheme(periodStart, periodEnd);
            case "GROUP"    -> aggregateGroup(periodStart, periodEnd);
            case "MEMBER"   -> aggregateMember(periodStart, periodEnd);
            case "PROVIDER" -> aggregateProvider(periodStart, periodEnd);
            default -> Flux.error(new IllegalArgumentException(
                    "dimension must be SCHEME|GROUP|MEMBER|PROVIDER, got '" + dimension + "'"));
        };
    }

    private Flux<ClaimsAggregateRow> aggregateScheme(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT 'SCHEME'      AS dimension,
                       c.scheme_id   AS dimension_id,
                       s.name        AS dimension_name,
                       c.currency_code AS currency_code,
                """ + FUNNEL + """
                  FROM claims c
                  JOIN schemes s ON s.id = c.scheme_id
                 WHERE """ + CLAIMS_PERIOD + """
                 GROUP BY c.scheme_id, s.name, c.currency_code
                 ORDER BY s.name, c.currency_code
                """;
        return bindPeriod(db.sql(sql), periodStart, periodEnd).map(this::toAggregateRow).all();
    }

    private Flux<ClaimsAggregateRow> aggregateGroup(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT 'GROUP'                   AS dimension,
                       g.id                      AS dimension_id,
                       COALESCE(g.name, 'Ungrouped') AS dimension_name,
                       c.currency_code           AS currency_code,
                """ + FUNNEL + """
                  FROM claims c
                  JOIN members m ON m.id = c.member_id
                  LEFT JOIN groups g ON g.id = m.group_id
                 WHERE """ + CLAIMS_PERIOD + """
                 GROUP BY g.id, g.name, c.currency_code
                 ORDER BY g.name NULLS LAST, c.currency_code
                """;
        return bindPeriod(db.sql(sql), periodStart, periodEnd).map(this::toAggregateRow).all();
    }

    private Flux<ClaimsAggregateRow> aggregateMember(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT 'MEMBER'                             AS dimension,
                       c.member_id                          AS dimension_id,
                       TRIM(m.first_name || ' ' || m.last_name) AS dimension_name,
                       c.currency_code                      AS currency_code,
                """ + FUNNEL + """
                  FROM claims c
                  JOIN members m ON m.id = c.member_id
                 WHERE """ + CLAIMS_PERIOD + """
                 GROUP BY c.member_id, m.first_name, m.last_name, c.currency_code
                 ORDER BY m.last_name, m.first_name, c.currency_code
                """;
        return bindPeriod(db.sql(sql), periodStart, periodEnd).map(this::toAggregateRow).all();
    }

    private Flux<ClaimsAggregateRow> aggregateProvider(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT 'PROVIDER'    AS dimension,
                       c.provider_id AS dimension_id,
                       p.name        AS dimension_name,
                       c.currency_code AS currency_code,
                """ + FUNNEL + """
                  FROM claims c
                  JOIN providers p ON p.id = c.provider_id
                 WHERE """ + CLAIMS_PERIOD + """
                 GROUP BY c.provider_id, p.name, c.currency_code
                 ORDER BY p.name, c.currency_code
                """;
        return bindPeriod(db.sql(sql), periodStart, periodEnd).map(this::toAggregateRow).all();
    }

    /**
     * Monthly-bucketed cross-service aggregate for Phase 8 cash-flow forecast
     * + KPI-dashboard consumers. {@code totalAmount} carries {@code total_paid}
     * — the primary loss-ratio consumer field (G44).
     */
    public Flux<MonthlyAggregateRow> aggregateMonthly(String dimension, LocalDate periodStart, LocalDate periodEnd) {
        return switch (dimension.toUpperCase()) {
            case "SCHEME"   -> aggregateMonthlyScheme(periodStart, periodEnd);
            case "GROUP"    -> aggregateMonthlyGroup(periodStart, periodEnd);
            case "MEMBER"   -> aggregateMonthlyMember(periodStart, periodEnd);
            case "PROVIDER" -> aggregateMonthlyProvider(periodStart, periodEnd);
            default -> Flux.error(new IllegalArgumentException(
                    "dimension must be SCHEME|GROUP|MEMBER|PROVIDER, got '" + dimension + "'"));
        };
    }

    private Flux<MonthlyAggregateRow> aggregateMonthlyScheme(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT 'SCHEME'      AS dimension,
                       c.scheme_id   AS dimension_id,
                       s.name        AS dimension_name,
                       c.currency_code AS currency_code,
                       date_trunc('month', c.adjudicated_at)::date AS month,
                       COALESCE(SUM(c.paid_amount), 0) AS total_amount
                  FROM claims c
                  JOIN schemes s ON s.id = c.scheme_id
                 WHERE """ + CLAIMS_PERIOD + """
                 GROUP BY c.scheme_id, s.name, c.currency_code, date_trunc('month', c.adjudicated_at)
                 ORDER BY month, s.name, c.currency_code
                """;
        return bindPeriod(db.sql(sql), periodStart, periodEnd).map(this::toMonthlyAggregate).all();
    }

    private Flux<MonthlyAggregateRow> aggregateMonthlyGroup(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT 'GROUP'                   AS dimension,
                       g.id                      AS dimension_id,
                       COALESCE(g.name, 'Ungrouped') AS dimension_name,
                       c.currency_code           AS currency_code,
                       date_trunc('month', c.adjudicated_at)::date AS month,
                       COALESCE(SUM(c.paid_amount), 0) AS total_amount
                  FROM claims c
                  JOIN members m ON m.id = c.member_id
                  LEFT JOIN groups g ON g.id = m.group_id
                 WHERE """ + CLAIMS_PERIOD + """
                 GROUP BY g.id, g.name, c.currency_code, date_trunc('month', c.adjudicated_at)
                 ORDER BY month, g.name NULLS LAST, c.currency_code
                """;
        return bindPeriod(db.sql(sql), periodStart, periodEnd).map(this::toMonthlyAggregate).all();
    }

    private Flux<MonthlyAggregateRow> aggregateMonthlyMember(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT 'MEMBER'                             AS dimension,
                       c.member_id                          AS dimension_id,
                       TRIM(m.first_name || ' ' || m.last_name) AS dimension_name,
                       c.currency_code                      AS currency_code,
                       date_trunc('month', c.adjudicated_at)::date AS month,
                       COALESCE(SUM(c.paid_amount), 0) AS total_amount
                  FROM claims c
                  JOIN members m ON m.id = c.member_id
                 WHERE """ + CLAIMS_PERIOD + """
                 GROUP BY c.member_id, m.first_name, m.last_name, c.currency_code,
                          date_trunc('month', c.adjudicated_at)
                 ORDER BY month, m.last_name, m.first_name, c.currency_code
                """;
        return bindPeriod(db.sql(sql), periodStart, periodEnd).map(this::toMonthlyAggregate).all();
    }

    private Flux<MonthlyAggregateRow> aggregateMonthlyProvider(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT 'PROVIDER'    AS dimension,
                       c.provider_id AS dimension_id,
                       p.name        AS dimension_name,
                       c.currency_code AS currency_code,
                       date_trunc('month', c.adjudicated_at)::date AS month,
                       COALESCE(SUM(c.paid_amount), 0) AS total_amount
                  FROM claims c
                  JOIN providers p ON p.id = c.provider_id
                 WHERE """ + CLAIMS_PERIOD + """
                 GROUP BY c.provider_id, p.name, c.currency_code, date_trunc('month', c.adjudicated_at)
                 ORDER BY month, p.name, c.currency_code
                """;
        return bindPeriod(db.sql(sql), periodStart, periodEnd).map(this::toMonthlyAggregate).all();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   HIGH_COST_CLAIMANT (G46)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Native-currency cumulative paid per (member, currency) across the
     * window. The threshold filter runs in the service layer post-FX-convert
     * (mixed-currency members need a per-row FX lookup that is not portable
     * inside SQL — see plan §5 rationale). {@code cumulativePaidReporting}
     * is left null here and filled by the service.
     */
    public Flux<HighCostClaimantRow> highCostMemberTotals(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                WITH member_totals AS (
                    SELECT c.member_id, c.currency_code,
                           SUM(c.paid_amount) AS native_paid,
                           COUNT(*)           AS contributing_claims
                      FROM claims c
                     WHERE c.adjudicated_at >= :periodStart
                       AND c.adjudicated_at <  (:periodEnd::date + INTERVAL '1 day')
                       AND c.paid_amount > 0
                     GROUP BY c.member_id, c.currency_code
                )
                SELECT m.id                       AS member_id,
                       m.member_number            AS member_number,
                       TRIM(m.first_name || ' ' || m.last_name) AS member_name,
                       mt.currency_code           AS currency_code,
                       mt.native_paid             AS cumulative_paid,
                       mt.contributing_claims     AS contributing_claims
                  FROM member_totals mt
                  JOIN members m ON m.id = mt.member_id
                 ORDER BY mt.native_paid DESC
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map(this::toHighCostRow)
                .all();
    }

    public Flux<ClaimsDetailResponse.ClaimLedgerRow> memberClaimLedger(UUID memberId,
                                                                       LocalDate periodStart, LocalDate periodEnd,
                                                                       int offset, int limit) {
        String sql = """
                SELECT c.id, c.claim_number,
                       TRIM(COALESCE(m.first_name, '') || ' ' || COALESCE(m.last_name, '')) AS member_name,
                       COALESCE(p.name, '')                        AS provider_name,
                       c.submission_date, c.service_date, c.adjudicated_at,
                       c.status, c.rejection_reason AS rejection_code,
                       c.claimed_amount, c.approved_amount, c.paid_amount,
                       c.currency_code
                  FROM claims c
                  LEFT JOIN members   m ON m.id = c.member_id
                  LEFT JOIN providers p ON p.id = c.provider_id
                 WHERE c.member_id = :memberId
                   AND c.adjudicated_at >= :periodStart
                   AND c.adjudicated_at <  (:periodEnd::date + INTERVAL '1 day')
                   AND c.paid_amount > 0
                 ORDER BY c.adjudicated_at DESC NULLS LAST, c.id DESC
                 OFFSET :offset LIMIT :limit
                """;
        return db.sql(sql)
                .bind("memberId", memberId)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .bind("offset", offset)
                .bind("limit", limit)
                .map(this::toLedgerRow)
                .all();
    }

    public Mono<Long> memberClaimLedgerCount(UUID memberId, LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT COUNT(*) AS total
                  FROM claims c
                 WHERE c.member_id = :memberId
                   AND c.adjudicated_at >= :periodStart
                   AND c.adjudicated_at <  (:periodEnd::date + INTERVAL '1 day')
                   AND c.paid_amount > 0
                """;
        return db.sql(sql)
                .bind("memberId", memberId)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map((row, meta) -> {
                    Long total = row.get("total", Long.class);
                    return total != null ? total : 0L;
                })
                .one();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   PRE_AUTH_ACTIVITY (G43)
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Per (status, currency) pre-auth activity on the {@code requested_date}
     * clock. {@code requested_date} is a DATE column so the period filter is
     * inclusive on both sides (no +1 day). Statuses are normalised to upper
     * case in the DTO (the DB CHECK constraint's case is not relied upon).
     */
    public Flux<PreAuthActivityRow> preAuthActivity(LocalDate periodStart, LocalDate periodEnd,
                                                    String status, UUID providerId) {
        String sql = """
                SELECT UPPER(pa.status)                    AS status,
                       COALESCE(pa.currency_code, '')      AS currency_code,
                       COUNT(*)                            AS count,
                       COALESCE(SUM(pa.requested_amount), 0) AS total_requested,
                       COALESCE(SUM(pa.approved_amount), 0) AS total_approved,
                       AVG(pa.decision_date - pa.requested_date) AS avg_decision_days
                  FROM pre_authorizations pa
                 WHERE pa.requested_date >= :periodStart
                   AND pa.requested_date <= :periodEnd
                   AND (:status IS NULL OR UPPER(pa.status) = :status)
                   AND (:providerId::uuid IS NULL OR pa.provider_id = :providerId::uuid)
                 GROUP BY UPPER(pa.status), pa.currency_code
                 ORDER BY UPPER(pa.status), pa.currency_code
                """;
        return bindNullProvider(bindNullStatus(
                        db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                        status), providerId)
                .map(this::toPreAuthRow)
                .all();
    }

    /**
     * Claims-side R04/R05 rejection signal for the same window — how often
     * claims were rejected because a pre-auth was required-but-missing (R04)
     * or had expired (R05).
     */
    public Mono<PreAuthActivityResponse.R04R05SignalRow> r04r05Signal(LocalDate periodStart, LocalDate periodEnd) {
        String sql = """
                SELECT COUNT(*) FILTER (WHERE c.rejection_reason = 'R04')                 AS r04_count,
                       COUNT(*) FILTER (WHERE c.rejection_reason = 'R05')                 AS r05_count,
                       COALESCE(SUM(c.claimed_amount)
                                FILTER (WHERE c.rejection_reason IN ('R04', 'R05')), 0)   AS total_claimed
                  FROM claims c
                 WHERE c.adjudicated_at >= :periodStart
                   AND c.adjudicated_at <  (:periodEnd::date + INTERVAL '1 day')
                """;
        return db.sql(sql)
                .bind("periodStart", periodStart)
                .bind("periodEnd", periodEnd)
                .map((row, meta) -> new PreAuthActivityResponse.R04R05SignalRow(
                        longOrZero(row, "r04_count"),
                        longOrZero(row, "r05_count"),
                        bigOrZero(row, "total_claimed")))
                .one();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   CLAIM_STATUS_LIST (G49) — pipeline aging matrix
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Matrix cells for the submission window — one per (status, age bucket,
     * currency). Ages are relative to {@code NOW()} at report time; statuses
     * normalise to upper case. Cells never merge currencies per G25 — the
     * nullable {@code currencyCode} contract on the DTO is honoured by never
     * producing a mixed-currency cell.
     */
    public Flux<ClaimStatusMatrixCell> statusMatrixCells(LocalDate submittedFrom, LocalDate submittedTo,
                                                         String insuranceLine) {
        String sql = ("""
                SELECT UPPER(c.status)  AS status,
                       %s               AS age_bucket,
                       c.currency_code  AS currency_code,
                       COUNT(*)         AS claim_count,
                """ + FUNNEL + """
                  FROM claims c
                 WHERE c.submission_date >= :submittedFrom
                   AND c.submission_date <  (:submittedTo::date + INTERVAL '1 day')
                   AND (:insuranceLine IS NULL OR c.insurance_line = :insuranceLine)
                 GROUP BY UPPER(c.status), age_bucket, c.currency_code
                 ORDER BY UPPER(c.status), age_bucket, c.currency_code
                """).formatted(AGE_BUCKET);
        return bindInsuranceLine(db.sql(sql)
                        .bind("submittedFrom", submittedFrom)
                        .bind("submittedTo", submittedTo), insuranceLine)
                .map(this::toStatusMatrixCell)
                .all();
    }

    /**
     * Paginated claim ledger for one status-matrix cell (or the whole window
     * when status / ageBucket are null — the export's full-window drill).
     * The age-bucket CASE is repeated in the WHERE so the drill is exactly
     * the ledger that built the clicked cell.
     */
    public Flux<ClaimsDetailResponse.ClaimLedgerRow> statusMatrixLedger(LocalDate submittedFrom, LocalDate submittedTo,
                                                                        String status, String ageBucket,
                                                                        int offset, int limit) {
        String sql = """
                SELECT c.id, c.claim_number,
                       TRIM(COALESCE(m.first_name, '') || ' ' || COALESCE(m.last_name, '')) AS member_name,
                       COALESCE(p.name, '')                        AS provider_name,
                       c.submission_date, c.service_date, c.adjudicated_at,
                       c.status, c.rejection_reason AS rejection_code,
                       c.claimed_amount, c.approved_amount, c.paid_amount,
                       c.currency_code
                  FROM claims c
                  LEFT JOIN members   m ON m.id = c.member_id
                  LEFT JOIN providers p ON p.id = c.provider_id
                 WHERE c.submission_date >= :submittedFrom
                   AND c.submission_date <  (:submittedTo::date + INTERVAL '1 day')
                   AND (:status IS NULL OR UPPER(c.status) = :status)
                   AND (:ageBucket IS NULL OR %s = :ageBucket)
                 ORDER BY c.submission_date DESC, c.id DESC
                 OFFSET :offset LIMIT :limit
                """.formatted(AGE_BUCKET);
        return bindNullAgeBucket(bindNullStatus(
                        db.sql(sql).bind("submittedFrom", submittedFrom).bind("submittedTo", submittedTo),
                        status), ageBucket)
                .bind("offset", offset)
                .bind("limit", limit)
                .map(this::toLedgerRow)
                .all();
    }

    public Mono<Long> statusMatrixLedgerCount(LocalDate submittedFrom, LocalDate submittedTo,
                                              String status, String ageBucket) {
        String sql = """
                SELECT COUNT(*) AS total
                  FROM claims c
                 WHERE c.submission_date >= :submittedFrom
                   AND c.submission_date <  (:submittedTo::date + INTERVAL '1 day')
                   AND (:status IS NULL OR UPPER(c.status) = :status)
                   AND (:ageBucket IS NULL OR %s = :ageBucket)
                """.formatted(AGE_BUCKET);
        return bindNullAgeBucket(bindNullStatus(
                        db.sql(sql).bind("submittedFrom", submittedFrom).bind("submittedTo", submittedTo),
                        status), ageBucket)
                .map((row, meta) -> {
                    Long total = row.get("total", Long.class);
                    return total != null ? total : 0L;
                })
                .one();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   DENIAL_ANALYSIS (G47) — three-view + monthly trend
    // ══════════════════════════════════════════════════════════════════════════

    private static final String DENIAL_PERIOD = """
             c.adjudicated_at >= :periodStart
            AND c.adjudicated_at <  (:periodEnd::date + INTERVAL '1 day')
            """;

    /** Denied-numerator filters shared by the category / code / provider views. */
    private static final String DENIAL_FILTERS = """
            AND (:category IS NULL OR r.category = :category)
            AND (:code IS NULL OR c.rejection_reason = :code)
            """;

    public Flux<DenialAnalysisResponse.CategoryRow> denialByCategory(LocalDate periodStart, LocalDate periodEnd,
                                                                     String category, String code, UUID providerId) {
        String sql = """
                SELECT r.category  AS category,
                       COUNT(*)    AS claim_count,
                       COALESCE(SUM(c.claimed_amount), 0) AS total_claimed
                  FROM claims c
                  JOIN rejection_reasons r ON r.code = c.rejection_reason
                 WHERE c.status = 'REJECTED'
                   AND """ + DENIAL_PERIOD + """
                   AND (:providerId::uuid IS NULL OR c.provider_id = :providerId::uuid)
                """ + DENIAL_FILTERS + """
                 GROUP BY r.category
                 ORDER BY r.category
                """;
        return bindNullCategory(bindNullCode(bindNullProvider(
                        db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                        providerId), code), category)
                .map(this::toDenialCategoryRow)
                .all();
    }

    public Flux<DenialAnalysisResponse.CodeRow> denialByCode(LocalDate periodStart, LocalDate periodEnd,
                                                             String category, String code, UUID providerId) {
        String sql = """
                SELECT r.code        AS code,
                       r.category    AS category,
                       r.description AS description,
                       COUNT(*)      AS claim_count,
                       COALESCE(SUM(c.claimed_amount), 0) AS total_claimed
                  FROM claims c
                  JOIN rejection_reasons r ON r.code = c.rejection_reason
                 WHERE c.status = 'REJECTED'
                   AND """ + DENIAL_PERIOD + """
                   AND (:providerId::uuid IS NULL OR c.provider_id = :providerId::uuid)
                """ + DENIAL_FILTERS + """
                 GROUP BY r.code, r.category, r.description
                 ORDER BY r.category, r.code
                """;
        return bindNullCategory(bindNullCode(bindNullProvider(
                        db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                        providerId), code), category)
                .map(this::toDenialCodeRow)
                .all();
    }

    /**
     * Provider view — denominator is the provider's full claim count in the
     * window (period + provider filters only); the denied numerator applies
     * the category/code filter via FILTER clauses so a category filter
     * narrows the denied count without shrinking the denominator.
     * {@code denial_rate_pct} is computed in the mapper from the two counts
     * (share ratio — always FX-safe per G47).
     */
    public Flux<DenialAnalysisResponse.ProviderRow> denialByProvider(LocalDate periodStart, LocalDate periodEnd,
                                                                     String category, String code, UUID providerId) {
        String sql = """
                SELECT c.provider_id AS provider_id,
                       COALESCE(p.name, '') AS provider_name,
                       COUNT(*) FILTER (WHERE c.status = 'REJECTED' AND (:category IS NULL OR r.category = :category)
                                                     AND (:code IS NULL OR c.rejection_reason = :code)) AS claim_count,
                       COALESCE(SUM(c.claimed_amount)
                                FILTER (WHERE c.status = 'REJECTED' AND (:category IS NULL OR r.category = :category)
                                                      AND (:code IS NULL OR c.rejection_reason = :code)), 0) AS total_claimed,
                       COUNT(*) AS total_count
                  FROM claims c
                  LEFT JOIN providers p ON p.id = c.provider_id
                  LEFT JOIN rejection_reasons r ON r.code = c.rejection_reason
                 WHERE """ + DENIAL_PERIOD + """
                   AND (:providerId::uuid IS NULL OR c.provider_id = :providerId::uuid)
                 GROUP BY c.provider_id, p.name
                HAVING COUNT(*) FILTER (WHERE c.status = 'REJECTED') > 0
                 ORDER BY total_claimed DESC NULLS LAST, p.name
                """;
        return bindNullCategory(bindNullCode(bindNullProvider(
                        db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                        providerId), code), category)
                .map(this::toDenialProviderRow)
                .all();
    }

    /**
     * Monthly denial trend on the {@code adjudicated_at} clock. The service
     * gates this query to multi-month windows only (G47) — a single-month
     * window renders an empty trend.
     */
    public Flux<DenialAnalysisResponse.MonthlyRow> denialMonthlyTrend(LocalDate periodStart, LocalDate periodEnd,
                                                                      String category, String code, UUID providerId) {
        String sql = """
                SELECT date_trunc('month', c.adjudicated_at)::date AS month,
                       COUNT(*)    AS claim_count,
                       COALESCE(SUM(c.claimed_amount), 0) AS total_claimed
                  FROM claims c
                  JOIN rejection_reasons r ON r.code = c.rejection_reason
                 WHERE c.status = 'REJECTED'
                   AND """ + DENIAL_PERIOD + """
                   AND (:providerId::uuid IS NULL OR c.provider_id = :providerId::uuid)
                """ + DENIAL_FILTERS + """
                 GROUP BY date_trunc('month', c.adjudicated_at)
                 ORDER BY 1
                """;
        return bindNullCategory(bindNullCode(bindNullProvider(
                        db.sql(sql).bind("periodStart", periodStart).bind("periodEnd", periodEnd),
                        providerId), code), category)
                .map(this::toDenialMonthlyRow)
                .all();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   CLAIMS_FREQUENCY_SEVERITY (G48) — scheme × line matrix
    // ══════════════════════════════════════════════════════════════════════════

    /**
     * Frequency + severity per (scheme, line, currency) on the service-date
     * clock. Exposure is the G48 fallback — {@code member_status_history}
     * does not exist, so it's {@code active_members × days / 30.4375} via a
     * LATERAL join. The service surfaces the fallback caveat on the
     * envelope's {@code warnings}. Percentiles are Postgres-native
     * {@code PERCENTILE_CONT} — never materialised client-side.
     */
    public Flux<FrequencySeverityRow> frequencySeverity(LocalDate serviceFrom, LocalDate serviceTo,
                                                        String insuranceLine, long days) {
        String sql = """
                SELECT a.scheme_id         AS scheme_id,
                       s.name              AS scheme_name,
                       a.insurance_line    AS insurance_line,
                       a.currency_code     AS currency_code,
                       a.claim_count       AS claim_count,
                       ROUND(exp.exposure_member_months, 2) AS exposure_member_months,
                       ROUND((a.claim_count::numeric / NULLIF(exp.exposure_member_months, 0)) * 12, 4) AS frequency,
                       ROUND(a.severity_mean, 2)  AS severity_mean,
                       ROUND(a.severity_median::numeric, 2) AS severity_median,
                       ROUND(a.severity_p95::numeric, 2)  AS severity_p95
                  FROM (
                       SELECT c.scheme_id, c.insurance_line, c.currency_code,
                              COUNT(*) AS claim_count,
                              AVG(c.approved_amount) AS severity_mean,
                              PERCENTILE_CONT(0.5)  WITHIN GROUP (ORDER BY c.approved_amount) AS severity_median,
                              PERCENTILE_CONT(0.95) WITHIN GROUP (ORDER BY c.approved_amount) AS severity_p95
                         FROM claims c
                        WHERE c.service_date >= :serviceFrom
                          AND c.service_date <  (:serviceTo::date + INTERVAL '1 day')
                          AND (:insuranceLine IS NULL OR c.insurance_line = :insuranceLine)
                        GROUP BY c.scheme_id, c.insurance_line, c.currency_code
                  ) a
                  JOIN schemes s ON s.id = a.scheme_id
                  JOIN LATERAL (
                       SELECT COUNT(*)::numeric * :days / 30.4375 AS exposure_member_months
                         FROM members m
                        WHERE m.scheme_id = a.scheme_id
                          AND m.status = 'ACTIVE'
                  ) exp ON true
                 ORDER BY s.name, a.insurance_line, a.currency_code
                """;
        return bindInsuranceLine(db.sql(sql)
                        .bind("serviceFrom", serviceFrom)
                        .bind("serviceTo", serviceTo)
                        .bind("days", days), insuranceLine)
                .map(this::toFrequencySeverityRow)
                .all();
    }

    // ══════════════════════════════════════════════════════════════════════════
    //   Internal helpers
    // ══════════════════════════════════════════════════════════════════════════

    private static void assertDimensionColumn(String col) {
        if (!"c.scheme_id".equals(col) && !"c.provider_id".equals(col)
                && !"m.group_id".equals(col) && !"c.member_id".equals(col)) {
            throw new IllegalArgumentException("Illegal dimension column: " + col);
        }
    }

    private static DatabaseClient.GenericExecuteSpec bindPeriod(DatabaseClient.GenericExecuteSpec spec,
                                                                LocalDate periodStart, LocalDate periodEnd) {
        return spec.bind("periodStart", periodStart).bind("periodEnd", periodEnd);
    }

    private static DatabaseClient.GenericExecuteSpec bindInsuranceLine(DatabaseClient.GenericExecuteSpec spec,
                                                                       String insuranceLine) {
        return (insuranceLine != null && !insuranceLine.isBlank())
                ? spec.bind("insuranceLine", insuranceLine)
                : spec.bindNull("insuranceLine", String.class);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullStatus(DatabaseClient.GenericExecuteSpec spec, String status) {
        return (status != null && !status.isBlank())
                ? spec.bind("status", status)
                : spec.bindNull("status", String.class);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullProvider(DatabaseClient.GenericExecuteSpec spec, UUID providerId) {
        return providerId != null
                ? spec.bind("providerId", providerId.toString())
                : spec.bindNull("providerId", String.class);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullCurrency(DatabaseClient.GenericExecuteSpec spec, String currencyCode) {
        return (currencyCode != null && !currencyCode.isBlank())
                ? spec.bind("currency", currencyCode)
                : spec.bindNull("currency", String.class);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullSearch(DatabaseClient.GenericExecuteSpec spec, String search) {
        return (search != null && !search.isBlank())
                ? spec.bind("search", search)
                : spec.bindNull("search", String.class);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullScheme(DatabaseClient.GenericExecuteSpec spec, UUID schemeId) {
        return schemeId != null
                ? spec.bind("schemeId", schemeId.toString())
                : spec.bindNull("schemeId", String.class);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullCategory(DatabaseClient.GenericExecuteSpec spec, String category) {
        return (category != null && !category.isBlank())
                ? spec.bind("category", category)
                : spec.bindNull("category", String.class);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullCode(DatabaseClient.GenericExecuteSpec spec, String code) {
        return (code != null && !code.isBlank())
                ? spec.bind("code", code)
                : spec.bindNull("code", String.class);
    }

    private static DatabaseClient.GenericExecuteSpec bindNullAgeBucket(DatabaseClient.GenericExecuteSpec spec, String ageBucket) {
        return (ageBucket != null && !ageBucket.isBlank())
                ? spec.bind("ageBucket", ageBucket)
                : spec.bindNull("ageBucket", String.class);
    }

    private ClaimsSummaryRow toSummaryRow(Readable row) {
        return new ClaimsSummaryRow(
                row.get("dimension_id", UUID.class),
                nullSafe(row.get("dimension_name", String.class)),
                row.get("insurance_line", String.class),
                nullSafe(row.get("currency_code", String.class)),
                longOrZero(row, "claim_count"),
                bigOrZero(row, "total_claimed"),
                bigOrZero(row, "total_approved"),
                bigOrZero(row, "total_paid"));
    }

    private ClaimStatusMatrixCell toStatusMatrixCell(Readable row) {
        return new ClaimStatusMatrixCell(
                nullSafe(row.get("status", String.class)),
                nullSafe(row.get("age_bucket", String.class)),
                longOrZero(row, "claim_count"),
                bigOrZero(row, "total_claimed"),
                bigOrZero(row, "total_approved"),
                bigOrZero(row, "total_paid"),
                row.get("currency_code", String.class));
    }

    private DenialAnalysisResponse.CategoryRow toDenialCategoryRow(Readable row) {
        return new DenialAnalysisResponse.CategoryRow(
                nullSafe(row.get("category", String.class)),
                longOrZero(row, "claim_count"),
                bigOrZero(row, "total_claimed"));
    }

    private DenialAnalysisResponse.CodeRow toDenialCodeRow(Readable row) {
        return new DenialAnalysisResponse.CodeRow(
                nullSafe(row.get("code", String.class)),
                nullSafe(row.get("category", String.class)),
                row.get("description", String.class),
                longOrZero(row, "claim_count"),
                bigOrZero(row, "total_claimed"));
    }

    private DenialAnalysisResponse.ProviderRow toDenialProviderRow(Readable row) {
        long denied = longOrZero(row, "claim_count");
        long total  = longOrZero(row, "total_count");
        BigDecimal rate = total > 0
                ? new BigDecimal(denied).multiply(new BigDecimal("100.00"))
                        .divide(new BigDecimal(total), 2, java.math.RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        return new DenialAnalysisResponse.ProviderRow(
                row.get("provider_id", UUID.class),
                nullSafe(row.get("provider_name", String.class)),
                denied,
                bigOrZero(row, "total_claimed"),
                rate);
    }

    private DenialAnalysisResponse.MonthlyRow toDenialMonthlyRow(Readable row) {
        return new DenialAnalysisResponse.MonthlyRow(
                row.get("month", LocalDate.class),
                longOrZero(row, "claim_count"),
                bigOrZero(row, "total_claimed"));
    }

    private FrequencySeverityRow toFrequencySeverityRow(Readable row) {
        return new FrequencySeverityRow(
                row.get("scheme_id", UUID.class),
                nullSafe(row.get("scheme_name", String.class)),
                row.get("insurance_line", String.class),
                bigOrZero(row, "exposure_member_months"),
                longOrZero(row, "claim_count"),
                bigOrZero(row, "frequency"),
                nullSafe(row.get("currency_code", String.class)),
                bigOrZero(row, "severity_mean"),
                bigOrZero(row, "severity_median"),
                bigOrZero(row, "severity_p95"));
    }

    private ClaimsAggregateRow toAggregateRow(Readable row) {
        return new ClaimsAggregateRow(
                nullSafe(row.get("dimension", String.class)),
                row.get("dimension_id", UUID.class),
                nullSafe(row.get("dimension_name", String.class)),
                nullSafe(row.get("currency_code", String.class)),
                bigOrZero(row, "total_claimed"),
                bigOrZero(row, "total_approved"),
                bigOrZero(row, "total_paid"));
    }

    private ClaimsDetailResponse.MonthlyBucket toMonthlyBucket(Readable row) {
        return new ClaimsDetailResponse.MonthlyBucket(
                row.get("month", LocalDate.class),
                nullSafe(row.get("currency_code", String.class)),
                longOrZero(row, "claim_count"),
                bigOrZero(row, "total_claimed"),
                bigOrZero(row, "total_approved"),
                bigOrZero(row, "total_paid"));
    }

    private ClaimsDetailResponse.ClaimLedgerRow toLedgerRow(Readable row) {
        return new ClaimsDetailResponse.ClaimLedgerRow(
                row.get("id", UUID.class),
                row.get("claim_number", String.class),
                nullSafe(row.get("member_name", String.class)),
                nullSafe(row.get("provider_name", String.class)),
                row.get("submission_date", Instant.class),
                row.get("service_date", LocalDate.class),
                row.get("adjudicated_at", Instant.class),
                nullSafe(row.get("status", String.class)),
                row.get("rejection_code", String.class),
                bigOrZero(row, "claimed_amount"),
                bigOrZero(row, "approved_amount"),
                bigOrZero(row, "paid_amount"),
                nullSafe(row.get("currency_code", String.class)));
    }

    private HighCostClaimantRow toHighCostRow(Readable row) {
        return new HighCostClaimantRow(
                row.get("member_id", UUID.class),
                row.get("member_number", String.class),
                nullSafe(row.get("member_name", String.class)),
                nullSafe(row.get("currency_code", String.class)),
                bigOrZero(row, "cumulative_paid"),
                longOrZero(row, "contributing_claims"),
                null);
    }

    private PreAuthActivityRow toPreAuthRow(Readable row) {
        return new PreAuthActivityRow(
                nullSafe(row.get("status", String.class)),
                nullSafe(row.get("currency_code", String.class)),
                longOrZero(row, "count"),
                bigOrZero(row, "total_requested"),
                bigOrZero(row, "total_approved"),
                row.get("avg_decision_days", BigDecimal.class),
                null,
                null);
    }

    private MonthlyAggregateRow toMonthlyAggregate(Readable row) {
        return new MonthlyAggregateRow(
                nullSafe(row.get("dimension", String.class)),
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
