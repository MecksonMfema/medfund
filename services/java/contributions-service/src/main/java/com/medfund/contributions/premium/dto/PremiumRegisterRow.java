package com.medfund.contributions.premium.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row per (policy × source × period) of the Phase 12 §B Premium
 * Register. Wraps an {@code earning_schedule} row enriched with the
 * policy's bind window, the member's friendly name, the scheme name, and
 * the IFRS 17 portfolio + cohort labels so the reader doesn't need to
 * chase UUIDs.
 *
 * <p>Rows stay native-currency (parent-plan invariant #1) — the envelope
 * carries per-currency subtotals + best-effort FX for optional client-
 * side conversion.
 *
 * <p>{@code isNewBusiness} is set by joining the policy's
 * {@code renewed_from_policy_id} chain (null → new; not null → renewal)
 * for annual-bind lines; HEALTH uses the member's first-ever
 * {@code Contribution} timestamp via the
 * {@code member_first_contribution} materialised view.
 */
public record PremiumRegisterRow(
        UUID policyId,
        String policySource,
        String memberName,
        String insuranceLine,
        String schemeName,
        String currencyCode,
        BigDecimal writtenPremium,
        BigDecimal earnedInPeriod,
        BigDecimal unearnedAtPeriodEnd,
        OffsetDateTime boundAt,
        LocalDate coverageStart,
        LocalDate coverageEnd,
        boolean isNewBusiness,
        String portfolioName,
        String cohortName,
        LocalDate periodStart,
        LocalDate periodEnd
) {}
