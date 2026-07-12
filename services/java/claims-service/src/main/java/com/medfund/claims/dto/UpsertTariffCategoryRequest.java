package com.medfund.claims.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * V063 request body for creating or updating a tariff_categories row.
 * {@code code} is the short uppercase identifier the resolver keys off,
 * {@code label} is what the operator sees on forms.
 */
public record UpsertTariffCategoryRequest(
        @NotBlank String code,
        @NotBlank String label,
        String description,
        Boolean isCapOnly,
        Boolean isActive,
        Integer sortOrder
) {
}
