package com.medfund.contributions.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload for creating a benefit-level cost-share row. Temporal insert (G15).
 *
 * <p>{@code copayType} is nullable — a benefit may carry coinsurance only, or
 * neither copay nor coinsurance (in which case the row still exists for the
 * applies-to-deductible / applies-to-oop_max flags). When non-null it must
 * be FLAT | PERCENT | TIERED; the DB check constraint enforces the enum.
 */
public record CreateBenefitCostShareRequest(
        String copayType,
        @DecimalMin("0") BigDecimal copayAmount,
        @DecimalMin("0") @DecimalMax("100") BigDecimal copayPercentage,
        @DecimalMin("0") BigDecimal copayMax,
        @DecimalMin("0") @DecimalMax("1") BigDecimal coinsuranceRate,
        Boolean appliesToDeductible,
        Boolean appliesToOopMax,
        String basis,
        @NotNull LocalDate effectiveFrom,
        LocalDate effectiveTo) {
}
