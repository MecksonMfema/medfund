package com.medfund.claims.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record UpsertDrugRequest(
        @NotBlank @Size(max = 200) String drugName,
        @Pattern(regexp = "ACUTE|CHRONIC|OFF_LIMIT") String drugType,
        @Pattern(regexp = "unit|ml|g|mg|tablet|capsule") String unitOfMeasurement,
        @Size(max = 50) String tariffCode,
        BigDecimal wholesaleCostZwl,
        BigDecimal wholesaleCostUsd,
        @DecimalMin("0") @DecimalMax("100") BigDecimal paymentPercentage,
        Boolean doNotPay,
        Boolean isActive
) {}
