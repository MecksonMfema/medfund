package com.medfund.finance.client;

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
 * Finance-service-local currency converter. Reads directly from
 * {@code public.exchange_rates} so cross-currency arithmetic (advance
 * thresholds vs advance amount, per-payee balance across currencies)
 * stays inside a single reactive pipeline without an HTTP hop to the
 * tenancy service.
 *
 * <p>Same lookup semantics as {@code ExchangeRateService.findLatest} in
 * tenancy-service: tenant-specific rate wins over platform-wide; falls
 * back to the most recent rate at or before {@code asOf}.
 *
 * <p>If no rate exists for the pair the Mono errors — finance-side callers
 * should decide whether to abort or fall through (typically abort, so a
 * silent zero doesn't corrupt a threshold decision or offset amount).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FxConverter {

    private final DatabaseClient databaseClient;

    /**
     * Convert {@code amount} from {@code fromCurrency} to {@code toCurrency}
     * using the rate effective on {@code asOf}. Same-currency short-circuits.
     */
    public Mono<BigDecimal> convert(BigDecimal amount, String fromCurrency, String toCurrency,
                                    LocalDate asOf, UUID tenantId) {
        if (amount == null) return Mono.just(BigDecimal.ZERO);
        if (fromCurrency == null || toCurrency == null || fromCurrency.equals(toCurrency)) {
            return Mono.just(amount);
        }
        return findLatestRate(fromCurrency, toCurrency, asOf, tenantId)
                .map(amount::multiply)
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "No exchange rate for " + fromCurrency + "->" + toCurrency + " as of " + asOf)));
    }

    private Mono<BigDecimal> findLatestRate(String base, String quote, LocalDate asOf, UUID tenantId) {
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
                .bind("asOf", asOf)
                .bind("tenantId", tenantId != null ? tenantId : Parameters.in(UUID.class))
                .map((row, meta) -> row.get("rate", BigDecimal.class))
                .one()
                .onErrorResume(err -> {
                    log.debug("[fx] rate lookup failed for {}->{} as of {}: {}",
                            base, quote, asOf, err.getMessage());
                    return Mono.empty();
                });
    }
}
