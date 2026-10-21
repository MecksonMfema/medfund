package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantMorbidityBasis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantMorbidityBasisResponse(
        UUID id,
        UUID tenantId,
        String insuranceLine,
        String basisName,
        BigDecimal morbidityMultiplier,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        OffsetDateTime updatedAt,
        String updatedByEmail
) {
    public static TenantMorbidityBasisResponse from(TenantMorbidityBasis row) {
        return new TenantMorbidityBasisResponse(
                row.getId(),
                row.getTenantId(),
                row.getInsuranceLine(),
                row.getBasisName(),
                row.getMorbidityMultiplier(),
                row.getEffectiveFrom(),
                row.getEffectiveTo(),
                row.getUpdatedAt(),
                row.getUpdatedByEmail()
        );
    }
}
