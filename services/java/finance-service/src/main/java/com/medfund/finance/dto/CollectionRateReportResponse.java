package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 3 collection-rate report payload. One block per dimension
 * (scheme, group, member); each dimension is a per-currency stack —
 * we never compute a cross-currency rate because that would require
 * FX conversion of the ratio itself, which the treasurer wants to
 * avoid seeing (G34).
 *
 * <p>Each dimension row carries a monthly-buckets strip so the
 * treasurer sees drift over the reporting window. Empty
 * {@code monthlyBuckets} lists are legitimate — they represent
 * dimensions that had billing but zero receipts (or vice-versa) for
 * a given month.
 */
public record CollectionRateReportResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        List<DimensionRow> byScheme,
        List<DimensionRow> byGroup,
        List<DimensionRow> byMember
) {

    /**
     * One (dimension, currency) block. {@code totalRatePct} is
     * {@code totalReceived / totalBilled * 100}; {@code null} when
     * {@code totalBilled} is zero (no meaningful denominator).
     */
    public record DimensionRow(
            UUID dimensionId,
            String dimensionName,
            String currencyCode,
            List<MonthlyBucket> monthlyBuckets,
            BigDecimal totalBilled,
            BigDecimal totalReceived,
            BigDecimal totalRatePct
    ) {}

    public record MonthlyBucket(
            LocalDate month,
            BigDecimal billed,
            BigDecimal received,
            BigDecimal ratePct
    ) {}
}
