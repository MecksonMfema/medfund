package com.medfund.contributions.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateSchemeBenefitRequest(
        @NotNull UUID schemeId,
        @NotBlank String name,
        @NotBlank String benefitType,
        BigDecimal annualLimit,
        BigDecimal dailyLimit,
        BigDecimal eventLimit,

        @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$",
                message = "currencyCode must be a 3-letter ISO 4217 code")
        String currencyCode,

        Integer waitingPeriodDays,
        String description,

        /** V051 age gate — AdjudicationPipeline Stage 3 rejects outside range. Null = unbounded. */
        @Min(0) @Max(120) Short minAge,
        @Min(0) @Max(120) Short maxAge,
        /** V051 payout gate — when false, CASH claims for this benefit reject with IN_KIND_ONLY. Null = true. */
        Boolean cashClaimAllowed
) {}
