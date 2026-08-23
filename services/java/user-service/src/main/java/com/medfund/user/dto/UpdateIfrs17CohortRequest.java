package com.medfund.user.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateIfrs17CohortRequest(
    @NotNull UUID portfolioId,
    @NotNull @Min(1900) @Max(2200) Integer cohortYear,
    @NotBlank @Pattern(regexp = "ONEROUS|NON_ONEROUS|UNCERTAIN",
                       message = "cohortType must be one of ONEROUS, NON_ONEROUS, UNCERTAIN")
    String cohortType,
    @NotBlank @Size(max = 200) String name
) {}
