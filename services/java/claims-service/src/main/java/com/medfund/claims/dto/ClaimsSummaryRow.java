package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One row of the CLAIMS_SUMMARY report — a funnel row per
 * (dimension, currency) over the reporting window. Dimensions: scheme,
 * group, member, provider (G45). {@code dimensionId} is the scheme /
 * provider / group / member id; {@code dimensionName} its display name.
 * {@code insuranceLine} is only populated on the MEMBER dimension (G45
 * cross-cut filter, populated by the report row when member-dimensioned).
 *
 * <p>Three-column funnel per G42 — every row carries claimed / approved /
 * paid. Amounts stay native-currency per G25; the envelope carries
 * per-currency ledger totals + best-effort reporting-currency FX.
 */
public record ClaimsSummaryRow(
        UUID   dimensionId,
        String dimensionName,
        String insuranceLine,
        String currencyCode,
        long   claimCount,
        BigDecimal totalClaimed,
        BigDecimal totalApproved,
        BigDecimal totalPaid
) {
}
