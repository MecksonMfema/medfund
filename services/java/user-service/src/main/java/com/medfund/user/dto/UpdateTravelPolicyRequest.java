package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateTravelPolicyRequest(
        @Size(max = 50)
        String policyNumber,

        LocalDate tripStartDate,

        LocalDate tripEndDate,

        @Size(max = 20)
        String destinationBand,

        @Size(max = 20)
        String coverageLevel,

        Boolean preExistingDeclared,

        UUID schemeId,

        UUID groupId,

        @DecimalMin(value = "0.01", message = "Override amount must be positive")
        @Digits(integer = 15, fraction = 4)
        BigDecimal billingOverrideAmount,

        @Size(max = 40)
        String billingOverrideReason,

        LocalDate billingOverrideEffectiveFrom
) {}
