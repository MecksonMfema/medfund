package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Row shape for the paginated claims list. Carries the joined member
 * and provider display fields so the client renders each row without
 * a second lookup — the previous unpaginated flow returned raw UUIDs
 * and the table cells showed 00000000-0000-... until an operator
 * clicked in.
 */
public record ClaimRow(
        UUID id,
        String claimNumber,
        UUID memberId,
        String memberName,
        String memberNumber,
        UUID dependantId,
        UUID providerId,
        String providerName,
        String payeeType,
        UUID schemeId,
        String claimType,
        String insuranceLine,
        String status,
        LocalDate serviceDate,
        Instant submissionDate,
        BigDecimal claimedAmount,
        BigDecimal approvedAmount,
        String currencyCode,
        String batchNumber,
        Instant createdAt
) {
}
