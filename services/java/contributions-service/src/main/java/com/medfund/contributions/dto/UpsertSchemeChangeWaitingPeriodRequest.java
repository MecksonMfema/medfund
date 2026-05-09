package com.medfund.contributions.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record UpsertSchemeChangeWaitingPeriodRequest(
        @NotBlank @Pattern(regexp = "^(UPGRADE|DOWNGRADE)$",
                message = "changeType must be UPGRADE or DOWNGRADE")
        String changeType,

        String benefitType,

        @NotNull @Min(0) Integer waitingDays,
        String description,
        Boolean isActive
) {}
