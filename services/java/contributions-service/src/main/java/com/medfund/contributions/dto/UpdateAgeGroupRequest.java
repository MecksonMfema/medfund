package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Payload for editing an existing age group. The scheme link is fixed at
 * creation time — moving an age group between schemes would change its
 * currency and invalidate every contribution that referenced it, so we
 * intentionally don't expose {@code schemeId} on update.
 */
public record UpdateAgeGroupRequest(
        @NotBlank String name,
        @NotNull Integer minAge,
        @NotNull Integer maxAge,
        @NotNull BigDecimal contributionAmount,

        @Size(min = 3, max = 3) @Pattern(regexp = "^[A-Z]{3}$",
                message = "currencyCode must be a 3-letter ISO 4217 code")
        String currencyCode
) {}
