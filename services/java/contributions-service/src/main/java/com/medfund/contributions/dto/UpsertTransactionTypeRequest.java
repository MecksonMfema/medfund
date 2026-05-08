package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpsertTransactionTypeRequest(
        @NotBlank @Size(max = 50) @Pattern(regexp = "^[A-Z0-9_]+$")
        String code,

        @NotBlank @Size(max = 200)
        String label,

        String description,

        @NotBlank @Pattern(regexp = "^[+-]$", message = "sign must be '+' or '-'")
        String sign,

        Boolean requiresApproval,
        Boolean isActive
) {}
