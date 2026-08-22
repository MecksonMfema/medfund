package com.medfund.contributions.controller;

import com.medfund.shared.report.FxRateReader;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cross-tenant analytics feeds for the super-admin platform dashboard.
 * Three endpoints — billing (issued invoices), billing payments (netted
 * completed transactions), and revenue-by-tenant (per-tenant top-N ranking).
 *
 * <p>Values are FX-converted server-side to USD using platform-wide rates
 * only ({@code tenant_id IS NULL} in {@code public.exchange_rates}). Rows
 * whose source currency cannot be converted are dropped and counted in the
 * envelope's {@code skipped} field so the gateway can log the miss.
 *
 * <p>The gateway (see {@code services/go/gateway/internal/platform/handler.go})
 * unwraps the {@code {rows, skipped}} envelope. For time-series endpoints
 * it also buckets rows into the requested chart period — this controller
 * stays period-agnostic per Phase 9 D9-5. Revenue-by-tenant is a ranking
 * (no bucketing) — the top-10 cap is applied here on the server so the
 * response is small.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
@Tag(name = "Platform Analytics", description = "Cross-tenant raw-row feeds for the super-admin analytics page — no tenant context")
public class PlatformAnalyticsController {

    private static final String USD = "USD";
    private static final int REVENUE_TOP_N = 10;

    /**
     * Receipts CTE mirroring {@link com.medfund.contributions.repository.ReceiptsReportQueryRepository}'s
     * definition — {@code PAYMENT}, {@code COPAYMENT_RECEIPT}, {@code CTC_OFFSET},
     * and their reversals, {@code status='completed'}, netted by
     * {@code transaction_types.sign}. The {@code %s} placeholder is
     * substituted with the tenant schema name (whitelist-checked upstream).
     */
    private static final String RECEIPTS_ROW_SQL_FMT = """
            SELECT t.transaction_date                                      AS ts,
                   CASE tt.sign WHEN '-' THEN t.amount ELSE -t.amount END  AS amount,
                   t.currency_code                                         AS currency_code
              FROM "%s".transactions t
              JOIN "%s".transaction_types tt ON tt.code = t.transaction_type
             WHERE tt.code IN ('PAYMENT','COPAYMENT_RECEIPT','CTC_OFFSET',
                                'REFUND','PAYMENT_REVERSAL','CTC_OFFSET_REVERSAL')
               AND t.status = 'completed'
               AND t.transaction_date IS NOT NULL
            """;

    private final DatabaseClient db;
    private final FxRateReader fxRateReader;

    // ═════════════════════════════════════════════════════════════════════════
    //   /billing-over-time — raw invoice (issued_at, USD amount)
    // ═════════════════════════════════════════════════════════════════════════

    @GetMapping("/billing-over-time")
    @Operation(summary = "Raw invoice issued_at + USD-converted total_amount across all tenants")
    public Mono<Map<String, Object>> getBillingOverTime(
            @RequestParam(required = false) LocalDate periodStart,
            @RequestParam(required = false) LocalDate periodEnd) {

        Map<String, BigDecimal> fxCache = new ConcurrentHashMap<>();
        AtomicLong skipped = new AtomicLong(0);

        return enumerateTenantSchemas()
                .flatMap(schema -> {
                    StringBuilder sql = new StringBuilder()
                            .append("SELECT issued_at AS ts, total_amount AS amount, currency_code AS currency_code")
                            .append(" FROM \"").append(schema).append("\".invoices")
                            .append(" WHERE issued_at IS NOT NULL AND status <> 'void'");
                    if (periodStart != null) sql.append(" AND issued_at >= :periodStart");
                    if (periodEnd != null)   sql.append(" AND issued_at < (:periodEnd::date + INTERVAL '1 day')");
                    var spec = db.sql(sql.toString());
                    if (periodStart != null) spec = spec.bind("periodStart", periodStart);
                    if (periodEnd != null)   spec = spec.bind("periodEnd", periodEnd);
                    return spec
                            .map((row, meta) -> new RawRow(
                                    row.get("ts", Instant.class),
                                    row.get("amount", BigDecimal.class),
                                    row.get("currency_code", String.class),
                                    null))
                            .all()
                            .onErrorResume(e -> {
                                log.debug("[platform-analytics] billing failed for {}: {}", schema, e.getMessage());
                                return Flux.empty();
                            });
                })
                .flatMap(raw -> convertToUsd(raw, fxCache, skipped))
                .collectList()
                .map(rows -> envelope(rows, skipped.get()));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //   /billing-payments-over-time — raw receipts (transaction_date, USD amount)
    // ═════════════════════════════════════════════════════════════════════════

    @GetMapping("/billing-payments-over-time")
    @Operation(summary = "Raw receipt timestamps + USD-converted netted amounts across all tenants")
    public Mono<Map<String, Object>> getBillingPaymentsOverTime(
            @RequestParam(required = false) LocalDate periodStart,
            @RequestParam(required = false) LocalDate periodEnd) {

        Map<String, BigDecimal> fxCache = new ConcurrentHashMap<>();
        AtomicLong skipped = new AtomicLong(0);

        return enumerateTenantSchemas()
                .flatMap(schema -> {
                    StringBuilder sql = new StringBuilder(RECEIPTS_ROW_SQL_FMT.formatted(schema, schema));
                    if (periodStart != null) sql.append(" AND t.transaction_date >= :periodStart");
                    if (periodEnd != null)   sql.append(" AND t.transaction_date < (:periodEnd::date + INTERVAL '1 day')");
                    var spec = db.sql(sql.toString());
                    if (periodStart != null) spec = spec.bind("periodStart", periodStart);
                    if (periodEnd != null)   spec = spec.bind("periodEnd", periodEnd);
                    return spec
                            .map((row, meta) -> new RawRow(
                                    row.get("ts", Instant.class),
                                    row.get("amount", BigDecimal.class),
                                    row.get("currency_code", String.class),
                                    null))
                            .all()
                            .onErrorResume(e -> {
                                log.debug("[platform-analytics] receipts failed for {}: {}", schema, e.getMessage());
                                return Flux.empty();
                            });
                })
                .flatMap(raw -> convertToUsd(raw, fxCache, skipped))
                .collectList()
                .map(rows -> envelope(rows, skipped.get()));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //   /revenue-by-tenant — per-tenant sum of receipts (USD), top-10 ranking
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Per-tenant sum of netted-receipts revenue in USD, top {@value #REVENUE_TOP_N}
     * rows sorted descending. Joins to {@code public.tenants} via
     * {@code schema_name} for the friendly display label; tenants without a
     * matching row fall through with schema name as a legible fallback.
     */
    @GetMapping("/revenue-by-tenant")
    @Operation(summary = "Top-10 tenants by USD-converted revenue for the platform analytics bar chart")
    public Mono<Map<String, Object>> getRevenueByTenant(
            @RequestParam(required = false) LocalDate periodStart,
            @RequestParam(required = false) LocalDate periodEnd) {

        Map<String, BigDecimal> fxCache = new ConcurrentHashMap<>();
        AtomicLong skipped = new AtomicLong(0);

        return enumerateTenantSchemas()
                .flatMap(schema -> {
                    StringBuilder sql = new StringBuilder(RECEIPTS_ROW_SQL_FMT.formatted(schema, schema));
                    if (periodStart != null) sql.append(" AND t.transaction_date >= :periodStart");
                    if (periodEnd != null)   sql.append(" AND t.transaction_date < (:periodEnd::date + INTERVAL '1 day')");
                    var spec = db.sql(sql.toString());
                    if (periodStart != null) spec = spec.bind("periodStart", periodStart);
                    if (periodEnd != null)   spec = spec.bind("periodEnd", periodEnd);
                    return spec
                            .map((row, meta) -> new RawRow(
                                    row.get("ts", Instant.class),
                                    row.get("amount", BigDecimal.class),
                                    row.get("currency_code", String.class),
                                    schema))
                            .all()
                            .onErrorResume(e -> {
                                log.debug("[platform-analytics] revenue failed for {}: {}", schema, e.getMessage());
                                return Flux.empty();
                            });
                })
                .flatMap(raw -> convertToUsdWithSchema(raw, fxCache, skipped))
                .groupBy(SchemaAmount::schema)
                .flatMap(g -> g.reduce(BigDecimal.ZERO, (acc, sa) -> acc.add(sa.amount()))
                        .map(total -> Map.entry(g.key(), total)))
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .flatMap(perSchema -> resolveSchemaNames(perSchema.keySet())
                        .map(schemaToName -> perSchema.entrySet().stream()
                                .map(e -> new TenantRevenue(
                                        schemaToName.getOrDefault(e.getKey(), e.getKey()),
                                        e.getValue()))
                                .sorted(Comparator.comparing(TenantRevenue::amount).reversed())
                                .limit(REVENUE_TOP_N)
                                .map(tr -> {
                                    Map<String, Object> row = new LinkedHashMap<>();
                                    row.put("tenantName", tr.name());
                                    row.put("value", tr.amount());
                                    return row;
                                })
                                .toList()))
                .map(rows -> envelope(rows, skipped.get()));
    }

    // ═════════════════════════════════════════════════════════════════════════
    //   Internals
    // ═════════════════════════════════════════════════════════════════════════

    private Flux<String> enumerateTenantSchemas() {
        return db.sql(
                        "SELECT schema_name FROM information_schema.schemata " +
                        "WHERE schema_name LIKE 'tenant_%' ORDER BY schema_name")
                .map(row -> row.get("schema_name", String.class))
                .all();
    }

    private Mono<Map<String, String>> resolveSchemaNames(java.util.Set<String> schemas) {
        if (schemas.isEmpty()) return Mono.just(Map.of());
        return db.sql("SELECT schema_name, name FROM public.tenants " +
                      "WHERE schema_name = ANY(:schemas)")
                .bind("schemas", schemas.toArray(new String[0]))
                .map((row, meta) -> Map.entry(
                        row.get("schema_name", String.class),
                        row.get("name", String.class)))
                .all()
                .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                .onErrorResume(e -> {
                    log.debug("[platform-analytics] tenant-name lookup failed: {}", e.getMessage());
                    return Mono.just(Map.of());
                });
    }

    Mono<Map<String, Object>> convertToUsd(RawRow raw,
                                           Map<String, BigDecimal> cache,
                                           AtomicLong skipped) {
        return convertRate(raw, cache, skipped)
                .map(usd -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("ts", raw.ts.toString());
                    row.put("value", usd);
                    return row;
                });
    }

    Mono<SchemaAmount> convertToUsdWithSchema(RawRow raw,
                                              Map<String, BigDecimal> cache,
                                              AtomicLong skipped) {
        return convertRate(raw, cache, skipped)
                .map(usd -> new SchemaAmount(raw.tenantSchema, usd));
    }

    private Mono<BigDecimal> convertRate(RawRow raw,
                                         Map<String, BigDecimal> cache,
                                         AtomicLong skipped) {
        if (raw.ts == null || raw.amount == null || raw.currencyCode == null) {
            skipped.incrementAndGet();
            return Mono.empty();
        }
        LocalDate date = raw.ts.atOffset(ZoneOffset.UTC).toLocalDate();
        String cacheKey = raw.currencyCode + "|" + date;
        BigDecimal cached = cache.get(cacheKey);
        if (cached != null) {
            return Mono.just(raw.amount.multiply(cached));
        }
        if (USD.equals(raw.currencyCode)) {
            cache.put(cacheKey, BigDecimal.ONE);
            return Mono.just(raw.amount);
        }
        return fxRateReader.findRate(raw.currencyCode, USD, date, null)
                .doOnNext(r -> cache.put(cacheKey, r))
                .map(raw.amount::multiply)
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    log.debug("[platform-analytics] no USD rate for {} on {} — dropping row",
                            raw.currencyCode, date);
                    skipped.incrementAndGet();
                }));
    }

    private static Map<String, Object> envelope(List<Map<String, Object>> rows, long skipped) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rows", rows);
        out.put("skipped", skipped);
        return out;
    }

    /** Raw row before FX conversion. {@code tenantSchema} is populated only for revenue-by-tenant. */
    record RawRow(Instant ts, BigDecimal amount, String currencyCode, String tenantSchema) {}

    /** Post-conversion per-schema row used by the revenue-by-tenant reducer. */
    record SchemaAmount(String schema, BigDecimal amount) {}

    /** Post-join per-tenant row used by the revenue-by-tenant top-N sort. */
    record TenantRevenue(String name, BigDecimal amount) {}
}
