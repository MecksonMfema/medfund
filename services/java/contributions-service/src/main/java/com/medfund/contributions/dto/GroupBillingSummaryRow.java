package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-group billing aggregate row, grouped by (group, currency).
 * Only committed contributions ({@code invoice_id IS NOT NULL}) are counted
 * per the Phase 2 plan — preview-only rows must not distort employer
 * reporting.
 */
public record GroupBillingSummaryRow(
        UUID groupId,
        String groupName,
        String currencyCode,
        long principalCount,
        long dependantCount,
        long livesCovered,
        BigDecimal totalBilled,
        BigDecimal totalPaid
) {
}
