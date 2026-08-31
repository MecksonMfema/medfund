package com.medfund.finance.regulatory.aml.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Compliance reviewer note attached to the RAISED → REVIEWED transition. */
public record ReviewAmlAlertRequest(
        @NotBlank @Size(min = 10, max = 2000,
                message = "reviewNote must be at least 10 characters")
        String reviewNote
) {}
