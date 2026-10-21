package com.medfund.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body for {@code PUT /api/v1/underwriting/variable-fees/{id}} — edit an
 * existing schedule row. To retarget the effective window, delete + re-add.
 */
public record UpdateVariableFeeScheduleRequest(
        @Schema(description = "Effective to (exclusive); null → open-ended")
        LocalDate effectiveTo,

        @Schema(description = "Fee as decimal fraction")
        @NotNull
        @DecimalMin(value = "0.0000")
        @DecimalMax(value = "1.0000")
        BigDecimal feePercentage
) {
}
