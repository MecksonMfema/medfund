package com.medfund.finance.controller;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cross-tenant analytics feeds for the super-admin platform dashboard.
 * Values are FX-converted server-side to USD (platform-wide rates only,
 * {@code tenant_id IS NULL} in {@code public.exchange_rates}). Rows whose
 * source currency cannot be converted are dropped and counted in the
 * envelope's {@code skipped} field so the gateway can log the miss.
 *
 * <p>The gateway (see {@code services/go/gateway/internal/platform/handler.go})
 * unwraps the {@code {rows, skipped}} envelope and buckets rows into the
 * requested chart period — this endpoint stays period-agnostic per Phase 9 D9-5.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform")
@RequiredArgsConstructor
@Tag(name = "Platform Analytics", description = "Cross-tenant raw-row feeds for the super-admin analytics page — no tenant context")
public class PlatformAnalyticsController {

    private static final String USD = "USD";

    private final DatabaseClient db;
    private final FxRateReader fxRateReader;

    /**
     * Raw {@code (ts, value)} rows of executed payment-run items across every
     * tenant schema. {@code ts} = {@code payment_runs.executed_at};
     * {@code value} = {@code payment_run_items.amount} converted to USD at
     * the run's {@code executed_at} date. Runs that never executed are
     * excluded; items that aren't {@code paid} are excluded.
     */
    @GetMapping("/claim-payouts-over-time")
    @Operation(summary = "Raw executed payment-run item timestamps + USD-converted amounts across all tenants")
    public Mono<Map<String, Object>> getClaimPayoutsOverTime(
            @RequestParam(required = false) LocalDate periodStart,
            @RequestParam(required = false) LocalDate periodEnd) {

        Map<String, BigDecimal> fxCache = new ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicLong skipped = new java.util.concurrent.atomic.AtomicLong(0);

        return db.sql(
                        "SELECT schema_name FROM information_schema.schemata " +
                        "WHERE schema_name LIKE 'tenant_%' ORDER BY schema_name")
                .map(row -> row.get("schema_name", String.class))
                .all()
                .flatMap(schema -> {
                    StringBuilder sql = new StringBuilder()
                            .append("SELECT r.executed_at AS ts, i.amount AS amount, i.currency_code AS currency_code")
                            .append(" FROM \"").append(schema).append("\".payment_run_items i")
                            .append(" JOIN \"").append(schema).append("\".payment_runs r ON r.id = i.payment_run_id")
                            .append(" WHERE i.status = 'paid' AND r.status = 'executed'")
                            .append(" AND r.executed_at IS NOT NULL");
                    if (periodStart != null) sql.append(" AND r.executed_at >= :periodStart");
                    if (periodEnd != null)   sql.append(" AND r.executed_at < (:periodEnd::date + INTERVAL '1 day')");
                    var spec = db.sql(sql.toString());
                    if (periodStart != null) spec = spec.bind("periodStart", periodStart);
                    if (periodEnd != null)   spec = spec.bind("periodEnd", periodEnd);
                    return spec
                            .map((row, meta) -> new RawRow(
                                    row.get("ts", Instant.class),
                                    row.get("amount", BigDecimal.class),
                                    row.get("currency_code", String.class)))
                            .all()
                            .onErrorResume(e -> {
                                log.debug("[platform-analytics] payouts query failed for {}: {}", schema, e.getMessage());
                                return Flux.empty();
                            });
                })
                .flatMap(raw -> convertToUsd(raw, fxCache, skipped))
                .collectList()
                .map(rows -> {
                    Map<String, Object> envelope = new LinkedHashMap<>();
                    envelope.put("rows", rows);
                    envelope.put("skipped", skipped.get());
                    return envelope;
                });
    }

    /**
     * Look up (or cache) the USD rate for the row's currency+date and multiply.
     * Same-currency short-circuits. Missing rate → skipped counter incremented
     * and the row dropped.
     */
    Mono<Map<String, Object>> convertToUsd(RawRow raw,
                                           Map<String, BigDecimal> cache,
                                           java.util.concurrent.atomic.AtomicLong skipped) {
        if (raw.ts == null || raw.amount == null || raw.currencyCode == null) {
            skipped.incrementAndGet();
            return Mono.empty();
        }
        LocalDate date = raw.ts.atOffset(ZoneOffset.UTC).toLocalDate();
        String cacheKey = raw.currencyCode + "|" + date;
        BigDecimal cached = cache.get(cacheKey);
        if (cached != null) {
            return Mono.just(row(raw.ts, raw.amount.multiply(cached)));
        }
        if (USD.equals(raw.currencyCode)) {
            cache.put(cacheKey, BigDecimal.ONE);
            return Mono.just(row(raw.ts, raw.amount));
        }
        return fxRateReader.findRate(raw.currencyCode, USD, date, null)
                .doOnNext(r -> cache.put(cacheKey, r))
                .map(r -> row(raw.ts, raw.amount.multiply(r)))
                .switchIfEmpty(Mono.fromRunnable(() -> {
                    log.debug("[platform-analytics] no USD rate for {} on {} — dropping row",
                            raw.currencyCode, date);
                    skipped.incrementAndGet();
                }));
    }

    private static Map<String, Object> row(Instant ts, BigDecimal usdAmount) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ts", ts.toString());
        out.put("value", usdAmount);
        return out;
    }

    /** Local raw-row tuple: pre-FX amount + source currency. */
    record RawRow(Instant ts, BigDecimal amount, String currencyCode) {}
}
