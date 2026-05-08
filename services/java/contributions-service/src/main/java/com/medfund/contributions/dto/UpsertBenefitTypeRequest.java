package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpsertBenefitTypeRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Z0-9_]+$",
                message = "code must be uppercase letters, numbers, and underscores")
        String code,

        @NotBlank @Size(max = 200)
        String label,

        String description,
        Integer sortOrder,
        Boolean isActive
) {}
