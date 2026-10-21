package com.medfund.tenancy.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Updates the mutable fields of a {@code tenant_yield_curve} row. The
 * (currency, tenor_months, effective_from) tuple is immutable — a change
 * to any of those is a new row rather than an in-place edit, preserving
 * the historical snapshot that Phase 6 cohort lock-in reads back from.
 */
public record UpdateTenantYieldCurveRequest(
        @NotNull BigDecimal spotRate,
        LocalDate effectiveTo
) {}
