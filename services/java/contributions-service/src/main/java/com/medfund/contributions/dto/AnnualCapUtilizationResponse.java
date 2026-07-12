package com.medfund.contributions.dto;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * V062 scheme-level annual cap utilization for one beneficiary in one
 * policy year. Consumed at {@code GET /api/v1/beneficiary-annual-totals/for}.
 * Powers the "Annual cap" row that sits above the per-benefit rows on
 * the claim-detail utilization card when the scheme has a cap set.
 *
 * <p>{@code capAmount} is the scheme's {@code annual_member_cap} — a
 * null value means the scheme opts out of the aggregate cap and the
 * UI omits the row entirely.
 */
public record AnnualCapUtilizationResponse(
        UUID schemeId,
        UUID memberId,
        UUID dependantId,
        Integer policyYear,
        BigDecimal consumedAmount,
        BigDecimal capAmount,
        String currencyCode
) {
}
