package com.medfund.finance.regulatory.aml.dto;

import com.medfund.finance.regulatory.aml.entity.TenantAmlThresholdConfig;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantAmlThresholdConfigResponse(
        UUID id,
        UUID tenantId,
        String transactionType,
        BigDecimal thresholdAmount,
        String currency,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String sourceNote,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        UUID actorId,
        String actorEmail
) {
    public static TenantAmlThresholdConfigResponse from(TenantAmlThresholdConfig row) {
        return new TenantAmlThresholdConfigResponse(
                row.getId(),
                row.getTenantId(),
                row.getTransactionType(),
                row.getThresholdAmount(),
                row.getCurrency(),
                row.getEffectiveFrom(),
                row.getEffectiveTo(),
                row.getSourceNote(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getActorId(),
                row.getActorEmail()
        );
    }
}
