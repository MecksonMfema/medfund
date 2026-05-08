package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateAgeGroupRequest(
        @NotNull UUID schemeId,
        @NotBlank String name,
        @NotNull Integer minAge,
        @NotNull Integer maxAge,
        @NotNull BigDecimal contributionAmount,

        @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$",
                message = "currencyCode must be a 3-letter ISO 4217 code")
        String currencyCode
) {}
