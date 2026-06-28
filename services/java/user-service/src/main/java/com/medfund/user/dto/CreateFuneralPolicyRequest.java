package com.medfund.user.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateFuneralPolicyRequest(
        @NotBlank @Size(max = 50)
        String policyNumber,

        @NotNull
        @DecimalMin(value = "0.00", inclusive = true)
        @Digits(integer = 15, fraction = 4)
        BigDecimal coverAmount,

        @NotNull @Min(1)
        Integer livesCovered,

        String healthDeclaration,

        @NotNull
        UUID schemeId,

        UUID groupId,

        @NotNull
        UUID principalMemberId
) {}
