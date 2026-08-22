package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WriteOffRecoveryRequest(@NotBlank @Size(max = 2000) String reason) {}
