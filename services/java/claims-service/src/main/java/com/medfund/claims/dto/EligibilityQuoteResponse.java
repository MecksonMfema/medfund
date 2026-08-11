package com.medfund.claims.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Response for {@code POST /api/v1/eligibility-quote}. Contains the
 * pre-service cost-share estimate produced by a read-only run through
 * the adjudication pipeline and the {@code CostShareCalculator}.
 *
 * <p>All monetary fields are in the caller's requested {@code currencyCode}.
 * {@code coverage} reports how the member is currently sitting with the
 * fund at the requested date of service ({@code ACTIVE}, {@code TERMINATED},
 * {@code IN_ARREARS}, {@code SUSPENDED}, {@code UNKNOWN}).
 */
public record EligibilityQuoteResponse(
        String coverage,
        String networkTier,
        BigDecimal deductibleRemaining,
        BigDecimal estimatedAllowed,
        BigDecimal estimatedCopay,
        BigDecimal estimatedCoinsurance,
        BigDecimal estimatedShortfall,
        BigDecimal estimatedPatientResponsibility,
        BigDecimal estimatedPlanPaid,
        BigDecimal oopMaxRemaining,
        String currencyCode,
        List<String> notes
) {
}
