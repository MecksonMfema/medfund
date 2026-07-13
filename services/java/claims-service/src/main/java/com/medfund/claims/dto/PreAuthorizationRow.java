package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Row shape returned by {@code GET /pre-authorizations/page}. Member +
 * provider names pre-joined server-side so the operational pre-auth
 * list renders inline.
 */
public record PreAuthorizationRow(
        UUID id,
        String authNumber,
        UUID memberId,
        String memberName,
        String memberNumber,
        UUID dependantId,
        UUID providerId,
        String providerName,
        UUID schemeId,
        String tariffCode,
        String diagnosisCode,
        String status,
        BigDecimal requestedAmount,
        BigDecimal approvedAmount,
        String currencyCode,
        LocalDate requestedDate,
        LocalDate decisionDate,
        LocalDate expiryDate,
        Instant createdAt
) {
}
