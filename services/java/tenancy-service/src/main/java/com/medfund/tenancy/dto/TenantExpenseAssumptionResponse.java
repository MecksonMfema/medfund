package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantExpenseAssumption;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantExpenseAssumptionResponse(
        UUID id,
        UUID tenantId,
        String insuranceLine,
        String expenseType,
        BigDecimal amountPerPolicy,
        String currency,
        String sourceNote,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        OffsetDateTime updatedAt,
        String updatedByEmail
) {
    public static TenantExpenseAssumptionResponse from(TenantExpenseAssumption row) {
        return new TenantExpenseAssumptionResponse(
                row.getId(),
                row.getTenantId(),
                row.getInsuranceLine(),
                row.getExpenseType(),
                row.getAmountPerPolicy(),
                row.getCurrency(),
                row.getSourceNote(),
                row.getEffectiveFrom(),
                row.getEffectiveTo(),
                row.getUpdatedAt(),
                row.getUpdatedByEmail()
        );
    }
}
