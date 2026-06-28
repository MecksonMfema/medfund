package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreatePropertyRequest(
        @NotBlank @Size(max = 200)
        String propertyName,

        @NotBlank
        String address,

        @NotNull
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

        @NotNull
        UUID schemeId,

        UUID groupId,

        UUID ownerMemberId
) {}
