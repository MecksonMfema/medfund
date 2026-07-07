package com.medfund.contributions.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpsertWaitingPeriodRequest(
        @NotNull UUID schemeId,
        @NotBlank String conditionType,
        @NotNull @Min(0) Integer waitingDays,
        String description,

        /** V052 age-band scoping. When either bound is set, this rule applies
         *  only to members whose age falls in [minAge, maxAge]. Null = universal. */
        @Min(0) @Max(120) Short minAge,
        @Min(0) @Max(120) Short maxAge
) {}
