package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantEndorsementConfig;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Read shape of {@code public.tenant_endorsement_config}. When no row
 * exists, {@link #unconfigured(UUID)} returns nulls — the tenant admin
 * page treats that as "endorsement four-eyes gate never configured,
 * defaults to disabled" and {@code PolicyEndorsementService.createDraft}
 * auto-commits.
 */
public record TenantEndorsementConfigResponse(
        UUID tenantId,
        boolean enabled,
        BigDecimal fourEyesThresholdAmount,
        String thresholdCurrency,
        OffsetDateTime updatedAt,
        UUID updatedBy,
        String updatedByEmail) {

    public static TenantEndorsementConfigResponse from(TenantEndorsementConfig c) {
        return new TenantEndorsementConfigResponse(
                c.getTenantId(),
                c.isEnabled(),
                c.getFourEyesThresholdAmount(),
                c.getThresholdCurrency(),
                c.getUpdatedAt(),
                c.getActorId(),
                c.getActorEmail());
    }

    /** Used when the tenant has no row — enabled=false, threshold null. */
    public static TenantEndorsementConfigResponse unconfigured(UUID tenantId) {
        return new TenantEndorsementConfigResponse(tenantId, false, null, null, null, null, null);
    }
}
