package com.medfund.shared.report;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Monthly-bucketed aggregate row shared between the billing and receipts
 * cross-service surfaces (Phase 3 collection-rate, Phase 8 cash-flow
 * forecast). Deliberately narrow: one bucket per (dimension, currency,
 * month). Amounts stay native-currency — the composing report resolves
 * FX at the envelope layer per G28.
 *
 * <p>{@code dimension} is one of {@code SCHEME}, {@code GROUP}, {@code MEMBER};
 * {@code dimensionId} is nullable for the synthetic {@code <UNALLOCATED>}
 * scheme (G33) that captures group-owned transactions without a
 * {@code contribution_id} back-link. {@code month} is always the
 * first-of-month bucket.
 */
public record MonthlyAggregateRow(
        String dimension,
        UUID dimensionId,
        String dimensionName,
        String currencyCode,
        LocalDate month,
        BigDecimal totalAmount
) {
}
