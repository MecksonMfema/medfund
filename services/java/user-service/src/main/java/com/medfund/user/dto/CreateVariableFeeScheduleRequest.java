package com.medfund.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body for {@code POST /api/v1/underwriting/funds/{fundId}/variable-fees} —
 * add an effective-date-versioned fee row. §16 VFA compute picks the row
 * whose window covers the reporting date.
 */
public record CreateVariableFeeScheduleRequest(
        @Schema(description = "Effective from (inclusive)", example = "2026-01-01")
        @NotNull
        LocalDate effectiveFrom,

        @Schema(description = "Effective to (exclusive); null → open-ended", example = "2027-01-01")
        LocalDate effectiveTo,

        @Schema(description = "Fee as decimal fraction — 0.0150 = 1.50% p.a.", example = "0.0150")
        @NotNull
        @DecimalMin(value = "0.0000", message = "fee_percentage must be >= 0")
        @DecimalMax(value = "1.0000", message = "fee_percentage must be <= 1")
        BigDecimal feePercentage
) {
}
