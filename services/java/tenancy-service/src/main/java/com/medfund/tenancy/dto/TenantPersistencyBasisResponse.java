package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantPersistencyBasis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantPersistencyBasisResponse(
        UUID id,
        UUID tenantId,
        String insuranceLine,
        Integer cohortMonths,
        BigDecimal expectedRetentionPct,
        String sourceNote,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        OffsetDateTime updatedAt,
        String updatedByEmail
) {
    public static TenantPersistencyBasisResponse from(TenantPersistencyBasis row) {
        return new TenantPersistencyBasisResponse(
                row.getId(),
                row.getTenantId(),
                row.getInsuranceLine(),
                row.getCohortMonths(),
                row.getExpectedRetentionPct(),
                row.getSourceNote(),
                row.getEffectiveFrom(),
                row.getEffectiveTo(),
                row.getUpdatedAt(),
                row.getUpdatedByEmail()
        );
    }
}
