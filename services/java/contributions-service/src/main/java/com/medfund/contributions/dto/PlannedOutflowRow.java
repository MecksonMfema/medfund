package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Mirror of finance-service's {@code PlannedOutflowRow} — one planned
 * payout item from a draft/approved payment run. Raw item level; the
 * finance-service feed deliberately does no aggregation so the Phase 8
 * cash-flow forecast buckets inflow and outflow by the same ISO weeks
 * (D8-5).
 */
public record PlannedOutflowRow(
        UUID runId,
        String runNumber,
        String currencyCode,
        BigDecimal amount,
        String runStatus,
        String itemStatus,
        Instant createdAt
) {
}
