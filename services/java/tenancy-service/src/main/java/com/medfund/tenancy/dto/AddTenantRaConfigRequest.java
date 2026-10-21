package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Adds one row to {@code tenant_ra_config} — the per-portfolio Risk
 * Adjustment methodology configured by the tenant. Methodology must be
 * either {@code COC} (Cost of Capital, requires {@code cocRate}) or
 * {@code CI} (Confidence Interval, requires {@code targetConfidenceLevel}).
 * The CHECK constraint at the row level enforces the mutually-exclusive
 * pair; conflicting insertions surface as HTTP 400 via the shared
 * exception handler.
 */
public record AddTenantRaConfigRequest(
        @NotNull UUID portfolioId,
        @NotBlank String methodology,
        @DecimalMin(value = "0.0", inclusive = false)
        @DecimalMax(value = "1.0", inclusive = false)
        BigDecimal cocRate,
        @DecimalMin(value = "0.0", inclusive = false)
        @DecimalMax(value = "1.0", inclusive = false)
        BigDecimal targetConfidenceLevel,
        @Size(max = 200) String sourceNote,
        LocalDate effectiveFrom,
        LocalDate effectiveTo
) {}
