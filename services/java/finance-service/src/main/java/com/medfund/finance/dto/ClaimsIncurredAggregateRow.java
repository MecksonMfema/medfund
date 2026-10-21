package com.medfund.finance.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Finance-side parallel of {@code claims.dto.ClaimsIncurredAggregateRow} —
 * one row of the {@code /aggregate/claims-incurred} feed consumed by the
 * Phase 18 KPI composer. {@code subtotalIncurredExIbnr} = paid + Δreserve;
 * IBNR is added downstream by the composer from {@code report_job.result_json}.
 * {@code claimCount} feeds CLAIMS_FREQUENCY (count / policy-months) and
 * AVERAGE_SEVERITY (paid / count).
 */
public record ClaimsIncurredAggregateRow(
        UUID schemeId,
        String schemeName,
        String insuranceLine,
        String currencyCode,
        BigDecimal totalPaid,
        BigDecimal reserveBalanceStart,
        BigDecimal reserveBalanceEnd,
        BigDecimal reserveMovement,
        BigDecimal subtotalIncurredExIbnr,
        long claimCount) {}
