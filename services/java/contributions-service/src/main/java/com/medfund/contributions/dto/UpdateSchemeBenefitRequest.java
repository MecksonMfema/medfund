package com.medfund.contributions.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
        String description,

        @Min(0) @Max(120) Short minAge,
        @Min(0) @Max(120) Short maxAge,
        Boolean cashClaimAllowed,

        /** V061 usage classification. Null on update = no change. */
        String usageMode
) {
    @AssertTrue(message = "usageMode must be RUNNING_BALANCE, ONE_TIME_PER_BENEFICIARY, ONE_TIME_PER_PERIOD, PER_EVENT_COUNTER, or NO_TRACKING")
    public boolean isUsageModeValid() {
        return usageMode == null
                || usageMode.equals("RUNNING_BALANCE")
                || usageMode.equals("ONE_TIME_PER_BENEFICIARY")
                || usageMode.equals("ONE_TIME_PER_PERIOD")
                || usageMode.equals("PER_EVENT_COUNTER")
                || usageMode.equals("NO_TRACKING");
    }
}
