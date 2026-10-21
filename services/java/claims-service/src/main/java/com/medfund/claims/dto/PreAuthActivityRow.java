package com.medfund.claims.dto;

import java.math.BigDecimal;

/**
 * One status bucket of the PRE_AUTH_ACTIVITY report. Statuses are the
 * canonical uppercase set (PENDING | APPROVED | REJECTED | EXPIRED);
 * {@code avgDecisionDays} is NULL for PENDING (no decision date yet).
 * Rate columns are populated where meaningful per status.
 */
public record PreAuthActivityRow(
        String status,                    // PENDING | APPROVED | REJECTED | EXPIRED
        String currencyCode,
        long   count,
        BigDecimal totalRequested,
        BigDecimal totalApproved,
        BigDecimal avgDecisionDays,       // NULL for PENDING
        BigDecimal approvalRatePct,
        BigDecimal expiryRatePct
) {
}
