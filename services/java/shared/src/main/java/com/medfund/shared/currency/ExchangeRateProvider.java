package com.medfund.shared.currency;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * SPI for sourcing exchange rates. The default implementation reads from the
 * {@code public.exchange_rates} table (manual entry only); future
 * implementations may scrape central-bank pages or call third-party APIs.
 *
 * <p>A provider returns {@link Mono#empty()} when it has no rate for the
 * requested triple; callers fall back to the next provider in priority order.
 */
public interface ExchangeRateProvider {

    /**
     * Stable identifier persisted on each {@code exchange_rates.source} row.
     * Examples: {@code "manual"}, {@code "RBZ"}, {@code "openexchangerates"}.
     */
    String sourceName();

    Mono<BigDecimal> fetchRate(String baseCurrency, String quoteCurrency, LocalDate date);
}
