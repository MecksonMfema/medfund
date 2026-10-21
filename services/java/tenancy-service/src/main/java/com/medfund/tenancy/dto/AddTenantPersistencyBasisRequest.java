package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Adds one row to {@code tenant_persistency_basis} — the per-tenant expected
 * retention curve consumed by the actuarial PERSISTENCY_STUDY report.
 * Uniqueness enforced by the underlying (tenant_id, insurance_line,
 * cohort_months, effective_from) constraint; a conflicting insert surfaces
 * as HTTP 409 via the shared exception handler.
 */
public record AddTenantPersistencyBasisRequest(
        @NotBlank String insuranceLine,
        @NotNull @Min(1) Integer cohortMonths,
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal expectedRetentionPct,
        @Size(max = 200) String sourceNote,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
