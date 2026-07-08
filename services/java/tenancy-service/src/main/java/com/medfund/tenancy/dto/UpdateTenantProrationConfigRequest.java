package com.medfund.tenancy.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * PUT payload — {@code defaultStrategy} is required; per-direction overrides
 * and {@code incrementWaitDays} are optional. All strategy names are validated
 * against the CHECK constraints on the table; the regex here just short-circuits
 * a malformed spelling before it hits the DB.
 */
public record UpdateTenantProrationConfigRequest(
        @NotBlank
        @Pattern(regexp = "NONE|DELTA_CREDIT|RATIO_CARRY|CALENDAR|SPLIT_YEAR|WAITING_PERIOD_ON_INCREMENT|HYBRID_BY_DIRECTION")
        String defaultStrategy,

        @Pattern(regexp = "NONE|DELTA_CREDIT|RATIO_CARRY|CALENDAR|SPLIT_YEAR|WAITING_PERIOD_ON_INCREMENT")
        String upgradeStrategy,

        @Pattern(regexp = "NONE|DELTA_CREDIT|RATIO_CARRY|CALENDAR|SPLIT_YEAR|WAITING_PERIOD_ON_INCREMENT")
        String downgradeStrategy,

        @Pattern(regexp = "NONE|DELTA_CREDIT|RATIO_CARRY|CALENDAR|SPLIT_YEAR|WAITING_PERIOD_ON_INCREMENT")
        String currencyStrategy,

        @Min(0)
        Integer incrementWaitDays) {
}
