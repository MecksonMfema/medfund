package com.medfund.user.endorsement.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Reason capture on a void action — mirrors {@code VoidAdjustmentRequest}
 * in finance-service (Phase 11 §B). The service still guards defensively
 * against blank reasons.
 */
public record VoidEndorsementRequest(
        @NotBlank @Size(min = 5, message = "reason must be at least 5 characters")
        String reason
) {}
