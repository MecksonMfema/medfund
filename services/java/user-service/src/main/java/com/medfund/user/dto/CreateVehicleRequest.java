package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateVehicleRequest(
        @NotBlank @Size(max = 32)
        String registrationNumber,

        @NotBlank @Size(max = 60)
        String make,

        @NotBlank @Size(max = 60)
        String model,

        @NotNull @Min(1900) @Max(2100)
        Integer year,

        @NotNull
        @DecimalMin(value = "0.00", inclusive = true)
        @Digits(integer = 15, fraction = 4)
        BigDecimal vehicleValue,

        @Size(max = 40)
        String bodyType,

        @Size(max = 40)
        String usageType,

        @NotNull
        UUID schemeId,

        UUID groupId,

        UUID ownerMemberId
) {}
