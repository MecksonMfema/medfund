package com.medfund.contributions.service.candidate;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Per-insured-entity row produced by a {@link CandidateResolver}. The
 * shape is line-agnostic: HEALTH populates memberId + dependantId,
 * VEHICLE populates memberId (owner) and leaves dependantId null and
 * carries the vehicle id in {@code memberNumber} as a friendly
 * identifier, PROPERTY does the same with property_name, life/funeral/
 * travel/disability policies carry the insured member's id and the
 * policy_number as friendly identifier.
 *
 * <p>{@code memberId} is nullable to allow truly standalone asset
 * policies (a property under a corporate cover that's not bound to a
 * person); resolvers should populate it when an owner_member exists
 * for accurate invoice routing.
 *
 * <p>{@code priceAmount}/{@code priceCurrency} come from each line's
 * own pricing source — age_group_prices for HEALTH, the asset's
 * vehicle_value for VEHICLE, sum_assured-band for LIFE, etc. May be
 * null when the line has no canonical bucket (a data gap), which
 * {@code BillingService.applyPricing} treats as zero so the row still
 * surfaces in the preview for the operator to triage.
 */
public record PersonCandidate(
        UUID memberId,
        UUID dependantId,
        String memberNumber,
        String personName,
        String personType,
        UUID schemeId,
        String schemeName,
        UUID groupId,
        String groupName,
        String schemeCurrency,
        UUID effectiveAgeGroupId,
        String ageBandName,
        BigDecimal priceAmount,
        String priceCurrency
) {}
