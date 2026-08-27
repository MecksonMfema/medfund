package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantMortalityBasis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantMortalityBasisResponse(
        UUID id,
        UUID tenantId,
        String insuranceLine,
        String basisName,
        BigDecimal mortalityMultiplier,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        OffsetDateTime updatedAt,
        String updatedByEmail
) {
    public static TenantMortalityBasisResponse from(TenantMortalityBasis row) {
        return new TenantMortalityBasisResponse(
                row.getId(),
                row.getTenantId(),
                row.getInsuranceLine(),
                row.getBasisName(),
                row.getMortalityMultiplier(),
                row.getEffectiveFrom(),
                row.getEffectiveTo(),
                row.getUpdatedAt(),
                row.getUpdatedByEmail()
        );
    }
}
