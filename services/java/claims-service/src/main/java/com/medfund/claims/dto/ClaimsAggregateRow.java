package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Rich narrow row for the cross-service {@code /aggregate/claims} endpoint
 * (Phase 4 §A, G44). One row per (dimension, dimensionId, dimensionName,
 * currencyCode) carrying all three funnel totals — Phase 5 loss-ratio can
 * pick paid-ratio or approved-liability-ratio without a second round trip.
 * {@code totalPaid} is the primary loss-ratio consumer field.
 */
public record ClaimsAggregateRow(
        String dimension,          // "SCHEME" | "GROUP" | "MEMBER" | "PROVIDER"
        UUID   dimensionId,
        String dimensionName,
        String currencyCode,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid
) {
}
