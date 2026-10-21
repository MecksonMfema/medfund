package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantRaConfig;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantRaConfigResponse(
        UUID id,
        UUID tenantId,
        UUID portfolioId,
        String methodology,
        BigDecimal cocRate,
        BigDecimal targetConfidenceLevel,
        String sourceNote,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        OffsetDateTime updatedAt,
        String updatedByEmail
) {
    public static TenantRaConfigResponse from(TenantRaConfig row) {
        return new TenantRaConfigResponse(
                row.getId(),
                row.getTenantId(),
                row.getPortfolioId(),
                row.getMethodology(),
                row.getCocRate(),
                row.getTargetConfidenceLevel(),
                row.getSourceNote(),
                row.getEffectiveFrom(),
                row.getEffectiveTo(),
                row.getUpdatedAt(),
                row.getUpdatedByEmail()
        );
    }
}
