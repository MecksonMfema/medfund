package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Adds one row to {@code tenant_morbidity_basis} — the per-tenant chosen
 * morbidity/incidence reference table (e.g. CIDA, GLTD87) plus a local
 * multiplier. Uniqueness enforced on (tenant_id, insurance_line,
 * effective_from).
 */
public record AddTenantMorbidityBasisRequest(
        @NotBlank String insuranceLine,
        @NotBlank @Size(max = 80) String basisName,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal morbidityMultiplier,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
