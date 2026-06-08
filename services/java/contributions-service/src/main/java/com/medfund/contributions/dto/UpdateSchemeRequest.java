package com.medfund.contributions.dto;

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
        LocalDate endDate,

        @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$",
                message = "currencyCode must be a 3-letter ISO 4217 code")
        String currencyCode
) {}
