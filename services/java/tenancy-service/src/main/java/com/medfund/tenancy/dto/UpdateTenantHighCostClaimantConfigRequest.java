package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * PUT payload for {@code /tenants/{tenantId}/high-cost-claimant-config}.
 * Both fields required. {@code currencyCode} is validated as an ISO-4217
 * code shape only — the tenant currency catalogue is not consulted here to
 * keep the config endpoint self-contained (same stance as
 * {@code UpdateTenantCtcAutoConfigRequest}).
 */
public record UpdateTenantHighCostClaimantConfigRequest(
        @NotNull
        @DecimalMin(value = "0.0", inclusive = true)
        BigDecimal thresholdAmount,

        @NotNull
        @Size(min = 3, max = 3)
        @Pattern(regexp = "[A-Z]{3}")
        String currencyCode) {
}
