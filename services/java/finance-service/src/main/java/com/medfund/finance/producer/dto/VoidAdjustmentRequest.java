package com.medfund.finance.producer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Reason capture on a void action. Enforced at controller-level so a
 * missing reason surfaces as a validation 400 before reaching the service.
 * The service still guards against blank reasons defensively.
 */
public record VoidAdjustmentRequest(
        @NotBlank @Size(min = 5, message = "reason must be at least 5 characters")
        String reason
) {}
