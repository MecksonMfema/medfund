package com.medfund.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/underwriting/cohorts/{id}/lock-in-yield-curve}.
 * Called by contributions-service on each {@code medfund.user.policy-issued}
 * event so the cohort's discount curve is captured at initial recognition
 * (IFRS 17.44). {@code effectiveDate} is the policy's coverage start.
 */
public record LockInYieldCurveRequest(
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be an ISO 4217 code")
        String currency,
        @NotNull LocalDate effectiveDate
) {}
