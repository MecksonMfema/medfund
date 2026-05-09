package com.medfund.claims.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertRejectionReasonRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank String description,
        @Size(max = 50) String category,
        Boolean isActive
) {}
