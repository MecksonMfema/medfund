package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Row shape for the member-liabilities list surface. Joins {@code members}
 * to include a friendly member name/number so the table doesn't have to
 * follow up with per-row lookups.
 */
public record MemberCostShareLiabilityRow(
        UUID id,
        UUID memberId,
        String memberName,
        String memberNumber,
        UUID claimId,
        String claimNumber,
        BigDecimal totalOwed,
        BigDecimal totalSettled,
        String currencyCode,
        String currencyCodeOriginal,
        String status,
        Instant createdAt,
        Instant updatedAt
) {
}
