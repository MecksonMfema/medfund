package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Phase 18 K9 aggregate row. {@code reserveMovement = reserveBalanceEnd -
 * reserveBalanceStart} per period boundary; {@code subtotalIncurredExIbnr
 * = totalPaid + reserveMovement} — the case-reserve-basis incurred figure.
 * IBNR is added downstream by {@code KpiComposerService}.
 *
 * <p>{@code claimCount} feeds both CLAIMS_FREQUENCY (count ÷ policy-months)
 * and AVERAGE_SEVERITY (paid ÷ count) — one endpoint feeds three KPIs.
 * {@code schemeId}, {@code schemeName}, {@code insuranceLine} are nullable
 * when the query dimension excludes them.
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
        long claimCount) {
}
