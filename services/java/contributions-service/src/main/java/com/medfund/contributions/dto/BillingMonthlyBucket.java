package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One month's slice of a scheme-detail or group-detail report. Rows
 * stay native-currency per G25 — cross-currency stratification happens
 * on the envelope's {@code perCurrency} / {@code fxRates} maps.
 */
public record BillingMonthlyBucket(
        LocalDate periodStart,
        String currencyCode,
        long principalCount,
        long dependantCount,
        BigDecimal totalBilled,
        BigDecimal totalPaid
) {
}
