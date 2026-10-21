package com.medfund.finance.regulatory.aml.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Payload for {@code POST /api/v1/tenants/{tenantId}/aml-threshold-config}.
 * DB CHECK regex + length constraints are mirrored client-side so callers
 * get a clean 400 rather than a Postgres constraint violation.
 */
public record AddTenantAmlThresholdConfigRequest(
        @NotBlank
        @Pattern(regexp = "PREMIUM|CLAIM_PAYOUT|ADVANCE_PAYMENT|REFUND|COMMISSION|ADJUSTMENT|OTHER",
                message = "must be one of PREMIUM|CLAIM_PAYOUT|ADVANCE_PAYMENT|REFUND|COMMISSION|ADJUSTMENT|OTHER")
        String transactionType,
        @DecimalMin(value = "0.01", message = "thresholdAmount must be > 0")
        BigDecimal thresholdAmount,
        @NotBlank
        @Pattern(regexp = "[A-Z]{3}", message = "currency must be ISO-4217 (3 uppercase letters)")
        String currency,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        @Size(max = 500) String sourceNote
) {}
