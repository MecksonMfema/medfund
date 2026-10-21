package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Narrow cross-service receipts aggregate row consumed by Phase 3 collection-rate
 * and Phase 5 loss-ratio reports. Symmetric to {@link BillingAggregateRow} — the
 * (dimension, currency) → net-received pair, netted per the
 * {@code transaction_types.sign} convention (F25).
 *
 * <p>{@code dimensionId} is nullable for the synthetic {@code <UNALLOCATED>}
 * scheme bucket that captures group-owned transactions without a
 * {@code contribution_id} back-link (G33).
 */
public record ReceiptsAggregateRow(
        String dimension,
        UUID dimensionId,
        String dimensionName,
        String currencyCode,
        BigDecimal totalReceived
) {
}
