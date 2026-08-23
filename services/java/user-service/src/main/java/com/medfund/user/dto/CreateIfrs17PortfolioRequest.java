package com.medfund.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateIfrs17PortfolioRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 2000) String description,
    @Pattern(regexp = "HEALTH|LIFE|FUNERAL|GROUP|TRAVEL|DISABILITY|VEHICLE|PROPERTY",
             message = "insuranceLine must be one of HEALTH, LIFE, FUNERAL, GROUP, TRAVEL, DISABILITY, VEHICLE, PROPERTY (or null for MISC catchall)")
    String insuranceLine
) {}
