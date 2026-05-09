package com.medfund.claims.dto;

import com.medfund.claims.entity.Drug;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DrugResponse(
        UUID id,
        String drugName,
        String drugType,
        String unitOfMeasurement,
        String tariffCode,
        BigDecimal wholesaleCostZwl,
        BigDecimal wholesaleCostUsd,
        BigDecimal paymentPercentage,
        Boolean doNotPay,
        Boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
    public static DrugResponse from(Drug d) {
        return new DrugResponse(
                d.getId(), d.getDrugName(), d.getDrugType(), d.getUnitOfMeasurement(),
                d.getTariffCode(), d.getWholesaleCostZwl(), d.getWholesaleCostUsd(),
                d.getPaymentPercentage(), d.getDoNotPay(), d.getIsActive(),
                d.getCreatedAt(), d.getUpdatedAt());
    }
}
