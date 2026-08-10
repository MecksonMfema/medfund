package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Projection returned by the paged {@code /payment-advices/page} endpoint.
 * Extends {@link PaymentAdviceRecordResponse}'s fields with joined
 * {@code payeeName} and {@code runNumber} so the Angular list can render
 * human-friendly labels without a follow-up round-trip.
 */
public record PaymentAdviceRowResponse(
        UUID id,
        String adviceNumber,
        UUID paymentRunId,
        String runNumber,
        String payeeType,
        UUID providerId,
        UUID memberId,
        String payeeName,
        String currencyCode,
        BigDecimal totalAmount,
        Integer claimCount,
        String status,
        Instant issuedAt,
        Instant periodStartAt,
        Instant periodEndAt,
        BigDecimal carriedInAmount,
        BigDecimal claimsPaidAmount,
        BigDecimal ctcAppliedAmount,
        BigDecimal advanceAppliedAmount,
        BigDecimal taxWithheldAmount,
        BigDecimal shortfallAmount,
        BigDecimal netDueAmount,
        Instant createdAt
) {
}
