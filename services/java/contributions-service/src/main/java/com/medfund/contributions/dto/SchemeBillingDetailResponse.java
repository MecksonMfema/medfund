package com.medfund.contributions.dto;

import java.util.List;
import java.util.UUID;

/**
 * Single-scheme detail — the summary row rolled up across the window
 * plus a monthly breakdown so the client can render a chart alongside
 * the totals card.
 */
public record SchemeBillingDetailResponse(
        UUID schemeId,
        String schemeName,
        String insuranceLine,
        List<SchemeBillingSummaryRow> perCurrencySummary,
        List<BillingMonthlyBucket> monthlyBreakdown
) {
}
