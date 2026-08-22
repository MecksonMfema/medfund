package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ResolveReviewTaskRequest(
        @NotBlank String resolution,
        @Size(max = 2000) String notes) {}
