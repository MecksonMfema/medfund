package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantMarketDataConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantMarketDataConfigResponse(
        UUID id,
        UUID tenantId,
        String currency,
        String source,
        Boolean autoFetchEnabled,
        OffsetDateTime updatedAt,
        String updatedByEmail
) {
    public static TenantMarketDataConfigResponse from(TenantMarketDataConfig row) {
        return new TenantMarketDataConfigResponse(
                row.getId(),
                row.getTenantId(),
                row.getCurrency(),
                row.getSource(),
                row.getAutoFetchEnabled(),
                row.getUpdatedAt(),
                row.getUpdatedByEmail()
        );
    }
}
