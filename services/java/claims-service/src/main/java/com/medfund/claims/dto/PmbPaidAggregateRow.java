package com.medfund.claims.dto;

import java.math.BigDecimal;

/**
 * Aggregate row for the {@code /reports/aggregate/pmb-paid} cross-service
 * feed consumed by finance-service's PMB Spend regulator report shaper.
 * One row per distinct ({@code isPmb}, {@code pmbConditionCode},
 * {@code currencyCode}) triple in the reporting window; the caller
 * transposes {@code pmbConditionCode} into a
 * {@code com.medfund.finance.regulatory.pmb.PmbCategory} rollup and sums
 * {@code paidAmount} per category (converted to ZAR downstream).
 *
 * <p>Period clock is {@code claims.adjudicated_at} (same convention as
 * {@link ClaimsIncurredAggregateRow}). Non-paid claims (paid_amount IS
 * NULL OR = 0) drop out so the aggregate only reflects money actually
 * moved on the tenant's book.
 */
public record PmbPaidAggregateRow(
        Boolean isPmb,
        String pmbConditionCode,
        String currencyCode,
        BigDecimal paidAmount,
        long claimCount) {
}
