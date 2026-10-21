package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Updates the mutable fields of a {@code tenant_expense_assumption} row.
 * The (insurance_line, expense_type, currency, effective_from) tuple is
 * immutable — moving the assumption to a new period is a new row rather
 * than an in-place edit.
 */
public record UpdateTenantExpenseAssumptionRequest(
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal amountPerPolicy,
        @Size(max = 200) String sourceNote,
        LocalDate effectiveTo
) {}
