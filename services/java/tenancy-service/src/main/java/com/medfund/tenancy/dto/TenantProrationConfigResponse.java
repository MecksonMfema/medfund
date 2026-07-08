package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantProrationConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantProrationConfigResponse(
        UUID tenantId,
        String defaultStrategy,
        String upgradeStrategy,
        String downgradeStrategy,
        String currencyStrategy,
        Integer incrementWaitDays,
        OffsetDateTime updatedAt,
        UUID updatedBy) {

    public static TenantProrationConfigResponse from(TenantProrationConfig c) {
        return new TenantProrationConfigResponse(
                c.getTenantId(),
                c.getDefaultStrategy(),
                c.getUpgradeStrategy(),
                c.getDowngradeStrategy(),
                c.getCurrencyStrategy(),
                c.getIncrementWaitDays(),
                c.getUpdatedAt(),
                c.getUpdatedBy());
    }

    /** Platform default used when the tenant has no row. */
    public static TenantProrationConfigResponse platformDefault(UUID tenantId) {
        return new TenantProrationConfigResponse(
                tenantId, "NONE", null, null, null, null, null, null);
    }
}
