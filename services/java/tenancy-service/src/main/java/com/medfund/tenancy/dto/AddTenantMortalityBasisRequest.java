package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Adds one row to {@code tenant_mortality_basis} — the per-tenant chosen
 * mortality reference table + a scalar multiplier that scales its qx values
 * for local experience. Uniqueness enforced on (tenant_id, insurance_line,
 * effective_from).
 */
public record AddTenantMortalityBasisRequest(
        @NotBlank String insuranceLine,
        @NotBlank @Size(max = 80) String basisName,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal mortalityMultiplier,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
