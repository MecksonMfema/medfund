package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateVehicleRequest(
        @Size(max = 60)
        String make,

        @Size(max = 60)
        String model,

        @Min(1900) @Max(2100)
        Integer year,

        @DecimalMin(value = "0.00", inclusive = true)
        @Digits(integer = 15, fraction = 4)
        BigDecimal vehicleValue,

        @Size(max = 40)
        String bodyType,

        @Size(max = 40)
        String usageType,

        UUID schemeId,

        UUID groupId,

        UUID ownerMemberId,

        @DecimalMin(value = "0.01", message = "Override amount must be positive")
        @Digits(integer = 15, fraction = 4)
        BigDecimal billingOverrideAmount,

        @Size(max = 40)
        String billingOverrideReason,

        LocalDate billingOverrideEffectiveFrom
) {}
