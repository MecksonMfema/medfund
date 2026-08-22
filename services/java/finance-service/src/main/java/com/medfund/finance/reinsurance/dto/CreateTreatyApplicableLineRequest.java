package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record CreateTreatyApplicableLineRequest(
        @NotBlank
        @Pattern(regexp = "HEALTH|LIFE|FUNERAL|GROUP|TRAVEL|DISABILITY|VEHICLE|PROPERTY")
        String insuranceLine
) {}
