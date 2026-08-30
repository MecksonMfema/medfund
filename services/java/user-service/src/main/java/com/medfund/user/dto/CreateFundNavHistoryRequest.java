package com.medfund.user.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Body for {@code POST /api/v1/underwriting/funds/{id}/nav-history} — append
 * a NAV row for a fund. Read-only after insert; corrections go via a new
 * valuation_date row.
 */
public record CreateFundNavHistoryRequest(
        @Schema(description = "Valuation date (not in the future)", example = "2026-08-27")
        @NotNull
        LocalDate valuationDate,

        @Schema(description = "NAV per unit (must be > 0)", example = "1.2340")
        @NotNull
        @DecimalMin(value = "0.000001", message = "nav_per_unit must be > 0")
        BigDecimal navPerUnit
) {
}
