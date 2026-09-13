package com.medfund.finance.dto;

import java.math.BigDecimal;

/**
 * Finance-side parallel of {@code claims.dto.PmbPaidAggregateRow} — one
 * row of the {@code /aggregate/pmb-paid} feed consumed by the real
 * {@code PmbSpendRawDataProvider}. Aggregates {@code SUM(paid_amount) +
 * COUNT(*)} on the tenant's claims table for the reporting window,
 * grouped by ({@code isPmb}, {@code pmbConditionCode}, {@code currencyCode}).
 *
 * <p>Non-PMB rows carry {@code isPmb=FALSE} and {@code pmbConditionCode=null}.
 * The caller sums those into the report's non-PMB bucket and rolls each
 * populated {@code pmbConditionCode} through
 * {@code PmbCategory.forCode(...)}.
 */
public record PmbPaidAggregateRow(
        Boolean isPmb,
        String pmbConditionCode,
        String currencyCode,
        BigDecimal paidAmount,
        long claimCount) {
}
