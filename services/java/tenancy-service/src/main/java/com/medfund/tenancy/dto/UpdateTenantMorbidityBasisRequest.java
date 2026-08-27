package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Updates the mutable fields of a {@code tenant_morbidity_basis} row.
 * The (insurance_line, effective_from) key is immutable.
 */
public record UpdateTenantMorbidityBasisRequest(
        @NotBlank @Size(max = 80) String basisName,
        @NotNull @DecimalMin(value = "0.0", inclusive = false) BigDecimal morbidityMultiplier,
        LocalDate effectiveTo
) {}
