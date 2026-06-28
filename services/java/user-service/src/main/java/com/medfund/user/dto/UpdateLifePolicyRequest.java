package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateLifePolicyRequest(
        @DecimalMin(value = "0.00", inclusive = true)
        @Digits(integer = 15, fraction = 4)
        BigDecimal sumAssured,

        @Size(max = 20)
        @Pattern(regexp = "SEDENTARY|MANUAL|HAZARDOUS|VERY_HAZARDOUS",
                message = "occupationHazardClass must be one of SEDENTARY, MANUAL, HAZARDOUS, VERY_HAZARDOUS")
        String occupationHazardClass,

        @Min(1)
        Integer termMonths,

        UUID schemeId,

        UUID groupId,

        @DecimalMin(value = "0.01", message = "Override amount must be positive")
        @Digits(integer = 15, fraction = 4)
        BigDecimal billingOverrideAmount,

        @Size(max = 40)
        String billingOverrideReason,

        LocalDate billingOverrideEffectiveFrom
) {}
