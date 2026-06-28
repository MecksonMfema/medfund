package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateFuneralPolicyRequest(
        @DecimalMin(value = "0.00", inclusive = true)
        @Digits(integer = 15, fraction = 4)
        BigDecimal coverAmount,

        @Min(1)
        Integer livesCovered,

        String healthDeclaration,

        UUID schemeId,

        UUID groupId,

        @DecimalMin(value = "0.01", message = "Override amount must be positive")
        @Digits(integer = 15, fraction = 4)
        BigDecimal billingOverrideAmount,

        @Size(max = 40)
        String billingOverrideReason,

        LocalDate billingOverrideEffectiveFrom
) {}
