package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Adds one row to {@code tenant_expense_assumption} — a per-line,
 * per-expense-type unit cost feeding the GMM fulfilment cash-flow
 * projection. Amounts are per-policy in the assumption's own currency;
 * the compute path converts to the reporting currency via
 * {@code exchange_rates} rather than doing any currency arithmetic here
 * (Rule 1: never mix currencies in arithmetic).
 */
public record AddTenantExpenseAssumptionRequest(
        @NotBlank String insuranceLine,
        @NotBlank String expenseType,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal amountPerPolicy,
        @NotBlank @Size(min = 3, max = 3) String currency,
        @Size(max = 200) String sourceNote,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
