package com.medfund.contributions.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload for creating a new scheme-level cost-share row. The plan is
 * strictly temporal (G15) — every mutation is an insert; there is no PUT.
 *
 * <p>{@code deductibleScope} and {@code oopScope} accept
 * INDIVIDUAL | FAMILY | EMBEDDED. {@code shortfallPolicy} accepts
 * RECOVER_FROM_MEMBER | ABSORB_BY_FUND (G11). Both are validated by the
 * DB check constraint so we don't repeat the enum here.
 */
public record CreateSchemeCostShareRequest(
        @NotNull @Min(1900) Integer policyYear,
        @DecimalMin("0") BigDecimal deductible,
        @DecimalMin("0") BigDecimal outOfPocketMax,
        @NotBlank String deductibleScope,
        @NotBlank String oopScope,
        @NotBlank String shortfallPolicy,
        @NotBlank @Size(min = 3, max = 3) String currencyCode,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
