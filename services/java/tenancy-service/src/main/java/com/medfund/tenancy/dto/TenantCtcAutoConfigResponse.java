package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantCtcAutoConfig;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantCtcAutoConfigResponse(
        UUID tenantId,
        boolean enabled,
        BigDecimal minMemberBalanceThreshold,
        BigDecimal maxPerCtcAmount,
        String thresholdCurrency,
        OffsetDateTime updatedAt,
        UUID updatedBy) {

    public static TenantCtcAutoConfigResponse from(TenantCtcAutoConfig c) {
        return new TenantCtcAutoConfigResponse(
                c.getTenantId(),
                Boolean.TRUE.equals(c.getEnabled()),
                c.getMinMemberBalanceThreshold(),
                c.getMaxPerCtcAmount(),
                c.getThresholdCurrency(),
                c.getUpdatedAt(),
                c.getUpdatedBy());
    }

    /** Platform default used when the tenant has no row (auto-drafting off). */
    public static TenantCtcAutoConfigResponse platformDefault(UUID tenantId) {
        return new TenantCtcAutoConfigResponse(
                tenantId, false, BigDecimal.ZERO, null, "USD", null, null);
    }
}
