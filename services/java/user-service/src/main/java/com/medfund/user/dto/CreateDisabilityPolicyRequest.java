package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateDisabilityPolicyRequest(
        @NotBlank @Size(max = 50)
        String policyNumber,

        @Size(max = 20)
        String occupationHazardClass,

        @NotNull @Min(0)
        Integer waitingPeriodDays,

        @Size(max = 20)
        String benefitPeriod,

        @NotNull
        @DecimalMin(value = "0.00", inclusive = true)
        @Digits(integer = 15, fraction = 4)
        BigDecimal monthlyBenefit,

        @NotNull
        UUID schemeId,

        UUID groupId,

        @NotNull
        UUID insuredMemberId
) {}
