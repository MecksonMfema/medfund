package com.medfund.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

public record CreateTravelPolicyRequest(
        @NotBlank @Size(max = 50)
        String policyNumber,

        @NotNull
        LocalDate tripStartDate,

        @NotNull
        LocalDate tripEndDate,

        @Size(max = 20)
        String destinationBand,

        @Size(max = 20)
        String coverageLevel,

        Boolean preExistingDeclared,

        @NotNull
        UUID schemeId,

        UUID groupId,

        @NotNull
        UUID travelerMemberId
) {}
