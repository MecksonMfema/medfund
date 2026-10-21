package com.medfund.user.endorsement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * DRAFT-time payload for a policy endorsement. {@code reason} must be at
 * least 10 characters (matches the {@code chk_endorsement_reason_len} DB
 * CHECK); enforced early at the service layer for a friendlier 400.
 *
 * <p>{@code premiumDelta} is signed and in the policy's native currency —
 * positive uplifts, negative reduces cover. When {@code premiumDelta} is
 * non-null, {@code currencyCode} must be present (paired). Non-financial
 * endorsements (BENEFICIARY_CHANGE, ADMIN_CHANGE) may omit both.
 *
 * <p>{@code effectiveFrom} snaps to 1st-of-month per
 * {@code feedback_effective_date_snap} — the service normalises after
 * validation.
 */
public record CreateEndorsementRequest(
        @NotNull UUID policyId,
        @NotBlank
        @Pattern(regexp = "^(LIFE_POLICY|FUNERAL_POLICY|DISABILITY_POLICY"
                       + "|TRAVEL_POLICY|VEHICLE_POLICY|PROPERTY_POLICY)$",
                 message = "policySource must be one of LIFE_POLICY, FUNERAL_POLICY, "
                         + "DISABILITY_POLICY, TRAVEL_POLICY, VEHICLE_POLICY, PROPERTY_POLICY")
        String policySource,
        @NotBlank String insuranceLine,
        @NotBlank
        @Pattern(regexp = "^(PREMIUM_ADJUSTMENT|COVERAGE_EXTENSION|BENEFIT_CHANGE"
                       + "|BENEFICIARY_CHANGE|ADMIN_CHANGE|PRODUCT_SWITCH|RENEWAL_ADVANCE)$",
                 message = "changeType must be one of the 7 declared arms")
        String changeType,
        @NotNull LocalDate effectiveFrom,
        BigDecimal premiumDelta,
        String currencyCode,
        @NotBlank
        @Size(min = 10, message = "reason must be at least 10 characters")
        String reason
) {}
