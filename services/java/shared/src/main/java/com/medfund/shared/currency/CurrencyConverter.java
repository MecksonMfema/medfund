package com.medfund.shared.currency;

import reactor.core.publisher.Mono;

import java.time.LocalDate;

/**
 * Converts a {@link Money} value into another currency using a date-stamped
 * exchange rate. Implementations must persist the rate id alongside any
 * transaction that consumes the result so historical reports can be
 * reconstructed exactly.
 */
public interface CurrencyConverter {

    /**
     * Convert {@code source} to {@code targetCurrency} using the rate effective
     * on {@code asOf}. If no rate is found for that exact date, the most recent
     * rate strictly before {@code asOf} is used.
     */
    Mono<Money> convert(Money source, String targetCurrency, LocalDate asOf);
}
