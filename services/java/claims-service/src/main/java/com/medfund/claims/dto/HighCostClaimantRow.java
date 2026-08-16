package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Row of the HIGH_COST_CLAIMANT report (Phase 4 §A, G46). One row per
 * member whose cumulative {@code paid_amount} across the window clears the
 * tenant-configured threshold once converted to the reporting currency.
 * The threshold comparison runs server-side post-convert
 * ({@code FxRateReader.convert} at period end — fail-loud on missing FX
 * per G28), so {@code cumulativePaid} stays native and
 * {@code cumulativePaidReporting} is the converted figure the filter used.
 */
public record HighCostClaimantRow(
        UUID   memberId,
        String memberNumber,
        String memberName,
        String currencyCode,
        BigDecimal cumulativePaid,
        long   contributingClaims,
        BigDecimal cumulativePaidReporting
) {
}
