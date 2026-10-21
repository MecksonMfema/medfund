package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantHighCostClaimantConfig;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantHighCostClaimantConfigResponse(
        UUID tenantId,
        BigDecimal thresholdAmount,
        String currencyCode,
        OffsetDateTime updatedAt,
        UUID updatedBy) {

    public static TenantHighCostClaimantConfigResponse from(TenantHighCostClaimantConfig c) {
        return new TenantHighCostClaimantConfigResponse(
                c.getTenantId(),
                c.getThresholdAmount(),
                c.getCurrencyCode(),
                c.getUpdatedAt(),
                c.getUpdatedBy());
    }

    /** Used when the tenant has no row — fields carry nulls, signalling "not configured". */
    public static TenantHighCostClaimantConfigResponse unconfigured(UUID tenantId) {
        return new TenantHighCostClaimantConfigResponse(tenantId, null, null, null, null);
    }
}
