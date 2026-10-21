package com.medfund.contributions.premium.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per policy (or first-Contribution for HEALTH) that first bound
 * within the reporting window per Phase 12 §B U6. For annual-bind lines
 * the filter is {@code renewed_from_policy_id IS NULL AND bound_at
 * BETWEEN periodStart AND periodEnd}; for HEALTH the filter uses the
 * {@code member_first_contribution} materialised view — {@code boundAt}
 * carries the first-Contribution instant, {@code memberNumber} + {@code
 * memberName} identify the person.
 *
 * <p>Native-currency per parent-plan invariant #1.
 */
public record NewBusinessRegisterRow(
        UUID policyId,
        String policySource,
        String memberNumber,
        String memberName,
        String insuranceLine,
        String schemeName,
        OffsetDateTime boundAt,
        BigDecimal writtenPremium,
        String currencyCode,
        String portfolioName,
        String cohortName,
        LocalDate coverageStart,
        LocalDate coverageEnd
) {}
