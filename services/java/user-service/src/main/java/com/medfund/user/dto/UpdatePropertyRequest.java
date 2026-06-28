package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdatePropertyRequest(
        @Size(max = 200)
        String propertyName,

        String address,

        @DecimalMin(value = "0.00", inclusive = true)
        @Digits(integer = 15, fraction = 4)
        BigDecimal sumInsured,

        @Size(max = 40)
        String constructionType,

        @Size(max = 40)
        String roofType,

        @Size(max = 20)
        String locationRiskBand,

        @Min(0)
        Integer securityFeaturesCount,

        @Min(0)
        Integer propertyAgeYears,

        @Size(max = 20)
        String occupancy,

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
