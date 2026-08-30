package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Adds one row (single tenor point) to {@code tenant_yield_curve}. A full
 * curve is many rows — one per tenor — sharing the same currency and
 * effective_from. Bulk import lives on the controller as
 * {@code POST .../csv-upload} rather than a single-payload nested-list
 * shape so per-row errors surface individually.
 */
public record AddTenantYieldCurveRequest(
        @NotBlank @Size(min = 3, max = 3) String currency,
        @NotNull @Min(1) @Max(600) Integer tenorMonths,
        @NotNull BigDecimal spotRate,
        @Size(max = 20) String source,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
