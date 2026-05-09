package com.medfund.contributions.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record UpsertWaitingPeriodRequest(
        @NotNull UUID schemeId,
        @NotBlank String conditionType,
        @NotNull @Min(0) Integer waitingDays,
        String description
) {}
