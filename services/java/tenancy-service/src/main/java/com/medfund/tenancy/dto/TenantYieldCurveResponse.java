package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantYieldCurve;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantYieldCurveResponse(
        UUID id,
        UUID tenantId,
        String currency,
        Integer tenorMonths,
        BigDecimal spotRate,
        String source,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        OffsetDateTime updatedAt,
        String updatedByEmail
) {
    public static TenantYieldCurveResponse from(TenantYieldCurve row) {
        return new TenantYieldCurveResponse(
                row.getId(),
                row.getTenantId(),
                row.getCurrency(),
                row.getTenorMonths(),
                row.getSpotRate(),
                row.getSource(),
                row.getEffectiveFrom(),
                row.getEffectiveTo(),
                row.getUpdatedAt(),
                row.getUpdatedByEmail()
        );
    }
}
