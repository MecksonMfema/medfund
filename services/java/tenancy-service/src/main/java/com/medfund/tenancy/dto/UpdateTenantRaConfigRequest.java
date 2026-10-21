package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Updates the mutable fields of a {@code tenant_ra_config} row. The tuple
 * key (portfolio_id, effective_from) and the methodology are immutable —
 * a switch between CoC and CI is a new row (add) at a fresh effective_from
 * rather than an in-place edit, which preserves the historical audit trail
 * of which methodology was applied to which reporting period.
 */
public record UpdateTenantRaConfigRequest(
        @DecimalMin(value = "0.0", inclusive = false)
        @DecimalMax(value = "1.0", inclusive = false)
        BigDecimal cocRate,
        @DecimalMin(value = "0.0", inclusive = false)
        @DecimalMax(value = "1.0", inclusive = false)
        BigDecimal targetConfidenceLevel,
        @Size(max = 200) String sourceNote,
        LocalDate effectiveTo
) {}
