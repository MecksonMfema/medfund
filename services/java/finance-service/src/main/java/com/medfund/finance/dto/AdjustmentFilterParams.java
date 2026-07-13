package com.medfund.finance.dto;

import java.util.UUID;

/**
 * Query filters for {@code GET /api/v1/adjustments/page}. Sort keys are
 * translated in {@link com.medfund.finance.repository.AdjustmentQueryRepository};
 * unknown keys fall back to {@code created_at DESC}.
 */
public record AdjustmentFilterParams(
        String status,
        String adjustmentType,
        UUID providerId,
        UUID memberId,
        String currencyCode,
        String q,
        String sortKey,
        String sortDirection,
        int page,
        int size
) {
}
