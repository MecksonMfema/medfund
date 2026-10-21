package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.UsTenantNaicConfig;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record UsTenantNaicConfigResponse(
        UUID id,
        UUID tenantId,
        String stateDomicile,
        String naicCompanyCode,
        String naicGroupCode,
        String fein,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String sourceNote,
        OffsetDateTime updatedAt,
        String updatedByEmail
) {
    public static UsTenantNaicConfigResponse from(UsTenantNaicConfig row) {
        return new UsTenantNaicConfigResponse(
                row.getId(),
                row.getTenantId(),
                row.getStateDomicile(),
                row.getNaicCompanyCode(),
                row.getNaicGroupCode(),
                row.getFein(),
                row.getEffectiveFrom(),
                row.getEffectiveTo(),
                row.getSourceNote(),
                row.getUpdatedAt(),
                row.getUpdatedByEmail()
        );
    }
}
