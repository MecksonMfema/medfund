package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Updates the mutable fields of a {@code tenant_persistency_basis} row.
 * The tuple key (insurance_line, cohort_months, effective_from) is immutable —
 * a change to the curve shape is a new row (add) rather than an in-place edit.
 */
public record UpdateTenantPersistencyBasisRequest(
        @NotNull @DecimalMin("0.0") @DecimalMax("1.0") BigDecimal expectedRetentionPct,
        @Size(max = 200) String sourceNote,
        LocalDate effectiveTo
) {}
