package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Drill-down payload for a single CLAIMS_SUMMARY dimension (scheme /
 * provider / group / member). Mirrors Phase 3 {@code ReceiptsDetailResponse}
 * shape (G40): monthly funnel buckets + paginated claim ledger.
 */
public record ClaimsDetailResponse(
        UUID   dimensionId,
        String dimensionName,
        List<MonthlyBucket> monthlyBuckets,
        PageResponse<ClaimLedgerRow> claims
) {

    public record MonthlyBucket(
            LocalDate month,
            String currencyCode,
            long   claimCount,
            BigDecimal totalClaimed,
            BigDecimal totalApproved,
            BigDecimal totalPaid
    ) {
    }

    public record ClaimLedgerRow(
            UUID id,
            String claimNumber,
            String memberName,
            String providerName,
            Instant submissionDate,
            LocalDate serviceDate,
            Instant adjudicatedAt,
            String status,
            String rejectionCode,
            BigDecimal claimedAmount,
            BigDecimal approvedAmount,
            BigDecimal paidAmount,
            String currencyCode
    ) {
    }
}
