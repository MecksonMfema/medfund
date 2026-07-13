package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row shape returned by {@code GET /adjustments/page}. Member + provider
 * display fields pre-joined server-side so the operational tables render
 * inline without a second lookup.
 */
public record AdjustmentRow(
        UUID id,
        String adjustmentNumber,
        UUID providerId,
        String providerName,
        UUID memberId,
        String memberName,
        String memberNumber,
        String adjustmentType,
        BigDecimal amount,
        String currencyCode,
        String reason,
        String status,
        UUID approvedBy,
        Instant approvedAt,
        Instant createdAt,
        Instant updatedAt,
        UUID createdBy
) {
}
