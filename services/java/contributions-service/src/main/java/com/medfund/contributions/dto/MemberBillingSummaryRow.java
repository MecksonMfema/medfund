package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-member billing aggregate row for the individual-line billing surface
 * introduced in Phase 3 §8 as the symmetry-fix for
 * {@link SchemeBillingSummaryRow} / {@link GroupBillingSummaryRow}. Covers
 * insurance lines that bill members directly (LIFE / TRAVEL / DISABILITY /
 * VEHICLE / PROPERTY / individual HEALTH) — everything
 * {@code Contribution.memberId} is non-null for.
 *
 * <p>Same "committed contributions only" rule as the scheme + group
 * variants: only rows where {@code invoice_id IS NOT NULL} count.
 */
public record MemberBillingSummaryRow(
        UUID memberId,
        String memberNumber,
        String memberName,
        String insuranceLine,
        String schemeName,
        String currencyCode,
        long contributionCount,
        BigDecimal totalBilled,
        BigDecimal totalPaid
) {
}
