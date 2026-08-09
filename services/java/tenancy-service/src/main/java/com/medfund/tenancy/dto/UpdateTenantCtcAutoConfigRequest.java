package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * PUT payload for {@code /tenants/{tenantId}/ctc-auto-config}. All fields
 * required except {@code maxPerCtcAmount} which is nullable (null = no
 * per-CTC cap). {@code thresholdCurrency} is validated as an ISO-4217 code
 * shape only — the tenant currency catalogue is not consulted here to keep
 * the config endpoint self-contained.
 */
public record UpdateTenantCtcAutoConfigRequest(
        @NotNull Boolean enabled,

        @NotNull
        @DecimalMin(value = "0.0", inclusive = true)
        BigDecimal minMemberBalanceThreshold,

        @DecimalMin(value = "0.01", inclusive = true)
        BigDecimal maxPerCtcAmount,

        @NotNull
        @Size(min = 3, max = 3)
        @Pattern(regexp = "[A-Z]{3}")
        String thresholdCurrency) {
}
