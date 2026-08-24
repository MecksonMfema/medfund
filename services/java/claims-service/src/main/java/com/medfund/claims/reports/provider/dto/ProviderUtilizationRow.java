package com.medfund.claims.reports.provider.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Phase 13 §C Phase 9 per L12 — one row per (provider, insurance_line,
 * currency) in the window. {@code providerName} + {@code networkTier}
 * are enriched via a batched {@code ProviderClient} call to user-service;
 * unresolved providers fall back to {@code Provider Unknown} / STANDARD
 * per grill note 6.
 */
public record ProviderUtilizationRow(
    UUID       providerId,
    String     providerName,
    String     networkTier,
    String     insuranceLine,
    String     currencyCode,
    long       claimCount,
    BigDecimal totalClaimed,
    BigDecimal totalPaid,
    long       denialCount,
    long       uniqueMembers
) {}
