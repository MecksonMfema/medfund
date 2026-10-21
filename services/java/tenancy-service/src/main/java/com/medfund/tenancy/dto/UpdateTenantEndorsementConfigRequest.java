package com.medfund.tenancy.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;

/**
 * PUT payload for {@code /tenants/{tenantId}/endorsement-config}.
 * Constraints mirror V134's CHECK clauses:
 * <ul>
 *   <li>{@code fourEyesThresholdAmount}: ≥ 0 (nullable when {@code enabled=false})</li>
 *   <li>{@code thresholdCurrency}: ISO 4217 3-letter code (paired with the amount)</li>
 * </ul>
 * When {@code enabled=true} both threshold + currency are required so the
 * gate has a comparison basis.
 */
public record UpdateTenantEndorsementConfigRequest(
        @NotNull
        Boolean enabled,

        @DecimalMin(value = "0.00", inclusive = true, message = "fourEyesThresholdAmount must be ≥ 0")
        BigDecimal fourEyesThresholdAmount,

        @Pattern(regexp = "^[A-Z]{3}$", message = "thresholdCurrency must be a 3-letter ISO code")
        String thresholdCurrency) {
}
