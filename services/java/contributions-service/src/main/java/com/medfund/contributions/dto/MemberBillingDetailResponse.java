package com.medfund.contributions.dto;

import java.util.List;
import java.util.UUID;

/**
 * Single-member billing detail — the drill-down from
 * {@code /reports/billing/members/{memberId}}. Same monthly-buckets shape
 * as {@link SchemeBillingDetailResponse} / {@link GroupBillingDetailResponse}
 * so the Angular detail component can reuse the header strip.
 */
public record MemberBillingDetailResponse(
        UUID memberId,
        String memberNumber,
        String memberName,
        String insuranceLine,
        List<MemberBillingSummaryRow> summary,
        List<BillingMonthlyBucket> monthly
) {
}
