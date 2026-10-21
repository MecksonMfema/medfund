package com.medfund.shared.report;

import io.r2dbc.spi.Parameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Read-only historical FX rate lookup against {@code public.exchange_rates}
 * (V112 — immutable per {@code (base_currency, quote_currency, rate_date,
 * source, tenant_id)}). Every report service reads through this class so
 * contributions-service, claims-service, and finance-service share one
 * lookup path rather than each hopping to tenancy-service over HTTP.
 *
 * <p>Two semantics are exposed side-by-side because G28 differentiates
 * them:
 *
 * <ul>
 *   <li>{@link #findRate(String, String, LocalDate, UUID)} — best-effort;
 *       empty on missing. Used to populate {@link ReportResponse#fxRates()}
 *       for optional client-side conversion. Missing rate names the miss
 *       in {@link ReportResponse#warnings()} but does not fail the report.</li>
 *   <li>{@link #convert(BigDecimal, String, String, LocalDate, UUID)} —
 *       fail-loud; errors on missing. Used when the server actually converts
 *       a scalar (a grand-total in the reporting currency). Missing rate
 *       throws so a silent zero can't corrupt an aggregate.</li>
 * </ul>
 *
 * <p>Precedence matches {@code ExchangeRateService.findLatest} in
 * tenancy-service and {@code FxConverter} in finance-service:
 * tenant-specific rate wins over platform-wide, ordered by
 * {@code rate_date DESC, created_at DESC}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FxRateReader {

    private final DatabaseClient databaseClient;

    /**
     * Best-effort rate lookup — returns empty Mono when no rate exists for
     * the pair. Same-currency short-circuits to {@code 1}. Never errors.
     */
    public Mono<BigDecimal> findRate(String base, String quote, LocalDate asOf, UUID tenantId) {
        if (base == null || quote == null) return Mono.empty();
        if (base.equals(quote)) return Mono.just(BigDecimal.ONE);
        LocalDate effectiveAsOf = asOf != null ? asOf : LocalDate.now();
        return databaseClient.sql("""
                SELECT rate FROM public.exchange_rates
                 WHERE base_currency  = :base
                   AND quote_currency = :quote
                   AND rate_date     <= :asOf
                   AND (tenant_id     = :tenantId OR tenant_id IS NULL)
                 ORDER BY tenant_id NULLS LAST, rate_date DESC, created_at DESC
                 LIMIT 1
                """)
                .bind("base", base)
                .bind("quote", quote)
                .bind("asOf", effectiveAsOf)
                .bind("tenantId", tenantId != null ? tenantId : Parameters.in(UUID.class))
                .map((row, meta) -> row.get("rate", BigDecimal.class))
                .one()
                .onErrorResume(err -> {
                    log.debug("[fx] rate lookup failed for {}->{} as of {}: {}",
                            base, quote, effectiveAsOf, err.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Fail-loud conversion — errors on missing rate. Same-currency and
     * null-amount short-circuits. For envelope population use
     * {@link #findRate} instead.
     */
    public Mono<BigDecimal> convert(BigDecimal amount, String fromCurrency, String toCurrency,
                                    LocalDate asOf, UUID tenantId) {
        if (amount == null) return Mono.just(BigDecimal.ZERO);
        if (fromCurrency == null || toCurrency == null || fromCurrency.equals(toCurrency)) {
            return Mono.just(amount);
        }
        return findRate(fromCurrency, toCurrency, asOf, tenantId)
                .map(amount::multiply)
                .switchIfEmpty(Mono.error(new ReportGenerationException(
                        "No exchange rate for " + fromCurrency + "->" + toCurrency
                                + " as of " + (asOf != null ? asOf : LocalDate.now()))));
    }
}
