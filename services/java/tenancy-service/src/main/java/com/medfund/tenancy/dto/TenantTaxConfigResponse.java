package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantTaxConfig;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantTaxConfigResponse(
        UUID id,
        UUID tenantId,
        String countryCode,
        String taxType,
        String transactionCategory,
        String currency,
        BigDecimal rate,
        boolean registered,
        String registrationNumber,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String sourceNote,
        OffsetDateTime updatedAt,
        String updatedByEmail
) {
    public static TenantTaxConfigResponse from(TenantTaxConfig row) {
        return new TenantTaxConfigResponse(
                row.getId(),
                row.getTenantId(),
                row.getCountryCode(),
                row.getTaxType(),
                row.getTransactionCategory(),
                row.getCurrency(),
                row.getRate(),
                row.isRegistered(),
                row.getRegistrationNumber(),
                row.getEffectiveFrom(),
                row.getEffectiveTo(),
                row.getSourceNote(),
                row.getUpdatedAt(),
                row.getUpdatedByEmail()
        );
    }
}
