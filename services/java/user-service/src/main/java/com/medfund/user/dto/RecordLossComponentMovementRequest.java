package com.medfund.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Body for {@code POST /api/v1/underwriting/cohorts/{id}/loss-component} —
 * MANUAL admin adjustment of the loss-component balance. AUTO transitions
 * from §15 ai-service compute bypass this endpoint and call the service
 * directly.
 */
public record RecordLossComponentMovementRequest(
        @Schema(description = "INITIAL_RECOGNITION | RELEASE | REVERSAL | RECLASSIFICATION_TO_NON_ONEROUS",
                example = "INITIAL_RECOGNITION")
        @NotBlank
        @Pattern(regexp = "INITIAL_RECOGNITION|RELEASE|REVERSAL|RECLASSIFICATION_TO_NON_ONEROUS")
        String movementType,

        @Schema(description = "Absolute amount; direction derived from movement_type", example = "50000.00")
        @NotNull
        @DecimalMin(value = "0.01", message = "amount must be positive")
        BigDecimal amount,

        @Schema(description = "ISO-4217 code", example = "USD")
        @NotBlank
        @Pattern(regexp = "[A-Z]{3}")
        String currency,

        @Schema(description = "Optional operator note captured on the audit event")
        @Size(max = 2000)
        String reasonNote
) {
}
