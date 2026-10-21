package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Phase 5 loss-ratio report payload (billing vs claims). One row per
 * (scheme, currency) with the full claims funnel — claimed, approved,
 * paid — so the treasurer sees where the paid/billed ratio sits and
 * which stage it leaks at. Rows stay native per-currency (G34).
 */
public record LossRatioReportResponse(
        LocalDate periodStart,
        LocalDate periodEnd,
        List<LossRatioRow> rows
) {

    /**
     * {@code paidRatioPct} is {@code totalPaid / totalBilled * 100},
     * 2dp; {@code null} when {@code totalBilled} is zero (no meaningful
     * denominator). {@code billedMinusPaid} is the shortfall (billed
     * above what claims actually paid out) — {@code null}-safe so a
     * row missing either leg still renders.
     */
    public record LossRatioRow(
            UUID schemeId,
            String schemeName,
            String currencyCode,
            BigDecimal totalBilled,
            BigDecimal totalClaimed,
            BigDecimal totalApproved,
            BigDecimal totalPaid,
            BigDecimal paidRatioPct,
            BigDecimal billedMinusPaid
    ) {}
}
