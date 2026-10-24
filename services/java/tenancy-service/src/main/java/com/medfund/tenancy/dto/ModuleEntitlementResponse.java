package com.medfund.tenancy.dto;

import com.medfund.shared.entitlement.ModuleKey;
import com.medfund.tenancy.entity.TenantModuleEntitlement;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ModuleEntitlementResponse(
        UUID tenantId,
        String moduleKey,
        String name,
        String description,
        boolean enabled,
        OffsetDateTime updatedAt,
        UUID updatedBy
) {
    public static ModuleEntitlementResponse from(TenantModuleEntitlement row) {
        ModuleKey meta = ModuleKey.parse(row.getModuleKey()).orElse(null);
        return new ModuleEntitlementResponse(
                row.getTenantId(),
                row.getModuleKey(),
                meta != null ? meta.getDisplayName() : row.getModuleKey(),
                meta != null ? meta.getDescription() : null,
                Boolean.TRUE.equals(row.getEnabled()),
                row.getUpdatedAt(),
                row.getUpdatedBy()
        );
    }
}
