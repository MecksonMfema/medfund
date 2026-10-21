package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-holder billing aggregate row, grouped by (holder, currency). A holder
 * is either a corporate/employer group (holderType = GROUP, holderId =
 * groups.id) or an individual policyholder (holderType = INDIVIDUAL,
 * holderId = members.id, name = "First Last"). Only committed contributions
 * ({@code invoice_id IS NOT NULL}) are counted per the Phase 2 plan — preview-only
 * rows must not distort holder reporting.
 *
 * <p>Field names {@code groupId} / {@code groupName} are retained for wire
 * compatibility; for INDIVIDUAL rows they carry the member's id + full name.
 */
public record GroupBillingSummaryRow(
        UUID groupId,
        String groupName,
        String holderType,
        String currencyCode,
        long principalCount,
        long dependantCount,
        long livesCovered,
        BigDecimal totalBilled,
        BigDecimal totalPaid
) {
}
