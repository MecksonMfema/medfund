package com.medfund.contributions.dto;

import com.medfund.shared.validation.EndOfMonth;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;

public record UpdateSchemeRequest(
        String name,
        String description,
        String schemeType,
        // Optional on update — null means "no change". See CreateSchemeRequest
        // for the validation gap (no tenant-settings cross-check).
        String insuranceLine,
        @EndOfMonth LocalDate endDate,

        @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$",
                message = "currencyCode must be a 3-letter ISO 4217 code")
        String currencyCode,

        /** V050 age-eligibility bounds. Null = "no change" per patch semantics. */
        @Min(0) @Max(120) Short minAge,
        @Min(0) @Max(120) Short maxAge
) {}
