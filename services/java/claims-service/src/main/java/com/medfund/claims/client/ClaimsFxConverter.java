package com.medfund.claims.client;

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
 * Claims-service-local currency converter. Mirrors finance-service's
 * {@code FxConverter} — reads directly from {@code public.exchange_rates}
 * so the adjudication hot path doesn't take an HTTP hop to tenancy-service
 * per claim line.
 *
 * <p>Used by {@code CostShareCalculator} when a benefit's
 * {@code benefit_cost_share.copay_amount} is stored in the benefit's currency
 * and the claim was submitted in a different one (G6). Same-currency
 * short-circuits. If no rate exists, the Mono errors so we never silently
 * price a shortfall at zero.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimsFxConverter {

    private final DatabaseClient databaseClient;

    /**
     * Convert {@code amount} from {@code fromCurrency} to {@code toCurrency}
     * using the rate effective on {@code asOf}. Same-currency short-circuits;
     * null amount short-circuits to {@link BigDecimal#ZERO}.
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
                    log.debug("[claims-fx] rate lookup failed for {}->{} as of {}: {}",
                            base, quote, asOf, err.getMessage());
                    return Mono.empty();
                });
    }
}
