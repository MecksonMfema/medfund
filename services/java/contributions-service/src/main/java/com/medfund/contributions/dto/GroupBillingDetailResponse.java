package com.medfund.contributions.dto;

import java.util.List;
import java.util.UUID;

/**
 * Single-group detail — the summary row rolled up across the window
 * plus a monthly breakdown.
 */
public record GroupBillingDetailResponse(
        UUID groupId,
        String groupName,
        List<GroupBillingSummaryRow> perCurrencySummary,
        List<BillingMonthlyBucket> monthlyBreakdown
) {
}
