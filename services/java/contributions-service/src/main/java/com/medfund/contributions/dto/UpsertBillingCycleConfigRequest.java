package com.medfund.contributions.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record UpsertBillingCycleConfigRequest(
        @NotBlank @Pattern(regexp = "^(MONTHLY|QUARTERLY|ANNUAL|CUSTOM)$")
        String frequency,

        @Min(1) @Max(28)
        Short dayOfMonth,

        Boolean autoGenerate,

        @Min(0)
        Short commitCooldownHours
) {}
