package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record CreateSchemeRequest(
        @NotBlank @Size(max = 200) String name,
        String description,
        String schemeType,
        @NotNull LocalDate effectiveDate,
        LocalDate endDate,

        @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$",
                message = "currencyCode must be a 3-letter ISO 4217 code")
        String currencyCode
) {
    public String schemeTypeOrDefault() {
        return (schemeType == null || schemeType.isBlank()) ? "medical_aid" : schemeType;
    }
}
