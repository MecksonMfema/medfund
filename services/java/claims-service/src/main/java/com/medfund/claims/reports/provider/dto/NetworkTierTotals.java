package com.medfund.claims.reports.provider.dto;

import java.math.BigDecimal;

/**
 * Phase 13 §C Phase 9 per L12 — per-tier summary row aggregating every
 * provider utilization row that landed on that tier.
 */
public record NetworkTierTotals(
    String     networkTier,
    long       providerCount,
    long       claimCount,
    BigDecimal totalClaimed,
    BigDecimal totalPaid,
    long       denialCount,
    long       uniqueMembers
) {}
