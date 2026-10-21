package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * PUT payload for {@code /tenants/{tenantId}/auto-lapse-config}.
 * Ranges mirror the CHECK constraints in V133:
 * <ul>
 *   <li>{@code arrearsThresholdMonths}: 1–60 (nullable when {@code enabled=false})</li>
 *   <li>{@code graceWindowDays}: 0–180 (nullable when {@code enabled=false})</li>
 * </ul>
 * When {@code enabled=false} both threshold + grace may be null (the
 * arrears sweep skips the tenant regardless of what they hold).
 */
public record UpdateTenantAutoLapseConfigRequest(
        @NotNull
        Boolean enabled,

        @Min(1) @Max(60)
        Integer arrearsThresholdMonths,

        @Min(0) @Max(180)
        Integer graceWindowDays) {
}
