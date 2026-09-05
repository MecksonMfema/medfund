package com.medfund.finance.kpi.dto;

import java.math.BigDecimal;

/**
 * K12: one per-currency native ratio + numerator + denominator, or the
 * composite reporting-currency scalar (currencyCode = the reporting currency
 * code, not a magic constant). Rows carrying a native currency stay
 * un-converted per G34.
 */
public record KpiValue(
        BigDecimal ratio,
        BigDecimal numerator,
        BigDecimal denominator,
        String currencyCode) {}
