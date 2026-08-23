package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantAutoLapseConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read shape of {@code public.tenant_auto_lapse_config}. When no row
 * exists, {@link #unconfigured(UUID)} returns nulls — the tenant admin
 * page treats that as "auto-lapse never configured, defaults to
 * disabled".
 */
public record TenantAutoLapseConfigResponse(
        UUID tenantId,
        boolean enabled,
        Integer arrearsThresholdMonths,
        Integer graceWindowDays,
        OffsetDateTime updatedAt,
        UUID updatedBy,
        String updatedByEmail) {

    public static TenantAutoLapseConfigResponse from(TenantAutoLapseConfig c) {
        return new TenantAutoLapseConfigResponse(
                c.getTenantId(),
                c.isEnabled(),
                c.getArrearsThresholdMonths(),
                c.getGraceWindowDays(),
                c.getUpdatedAt(),
                c.getActorId(),
                c.getActorEmail());
    }

    /** Used when the tenant has no row — enabled=false, other fields null. */
    public static TenantAutoLapseConfigResponse unconfigured(UUID tenantId) {
        return new TenantAutoLapseConfigResponse(tenantId, false, null, null, null, null, null);
    }
}
