package com.medfund.finance.regulatory.aml.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Mutable subset of {@link AddTenantAmlThresholdConfigRequest} — a
 * threshold row's {@code transactionType} and {@code currency} are
 * treated as immutable (they participate in the UNIQUE key). Rate,
 * effective-to, and source note are the safe mutations.
 */
public record UpdateTenantAmlThresholdConfigRequest(
        @DecimalMin(value = "0.01", message = "thresholdAmount must be > 0")
        BigDecimal thresholdAmount,
        LocalDate effectiveTo,
        @Size(max = 500) String sourceNote
) {}
