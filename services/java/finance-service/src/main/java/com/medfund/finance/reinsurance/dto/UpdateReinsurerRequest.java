package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateReinsurerRequest(
        @NotBlank @Size(max = 200) String name,
        @Email @Size(max = 255) String contactEmail,
        String contactAddress,
        @Size(max = 20) String jurisdictionCode,
        @Size(min = 3, max = 3) String homeCurrency,
        @Size(max = 20) String creditRating,
        @NotNull Boolean active
) {}
