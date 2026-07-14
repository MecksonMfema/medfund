package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Row shape returned by {@code GET /contributions/page}. Member +
 * scheme + group display fields pre-joined server-side so the
 * statements table renders inline without a second lookup.
 */
public record ContributionRow(
        UUID id,
        UUID memberId,
        String memberName,
        String memberNumber,
        UUID groupId,
        String groupName,
        UUID schemeId,
        String schemeName,
        BigDecimal amount,
        String currencyCode,
        LocalDate periodStart,
        LocalDate periodEnd,
        String status,
        String paymentMethod,
        String paymentReference,
        Instant paidAt,
        Instant createdAt,
        Instant updatedAt
) {
}
