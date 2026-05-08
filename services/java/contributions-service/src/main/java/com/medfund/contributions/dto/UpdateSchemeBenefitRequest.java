package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Update payload for an existing scheme benefit. Currency cannot change once
 * the benefit is created — it inherits from the parent scheme and the parent
 * scheme is single-currency by design.
 */
public record UpdateSchemeBenefitRequest(
        @NotBlank String name,
        @NotBlank String benefitType,
        BigDecimal annualLimit,
        BigDecimal dailyLimit,
        BigDecimal eventLimit,

        @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$",
                message = "currencyCode must be a 3-letter ISO 4217 code")
        String currencyCode,

        Integer waitingPeriodDays,
        String description
) {}
