package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateDisabilityPolicyRequest(
        @Size(max = 50)
        String policyNumber,

        @Size(max = 20)
        String occupationHazardClass,

        @Min(0)
        Integer waitingPeriodDays,

        @Size(max = 20)
        String benefitPeriod,

        @DecimalMin(value = "0.00", inclusive = true)
        @Digits(integer = 15, fraction = 4)
        BigDecimal monthlyBenefit,

        UUID schemeId,

        UUID groupId,

        @DecimalMin(value = "0.01", message = "Override amount must be positive")
        @Digits(integer = 15, fraction = 4)
        BigDecimal billingOverrideAmount,

        @Size(max = 40)
        String billingOverrideReason,

        LocalDate billingOverrideEffectiveFrom
) {}
