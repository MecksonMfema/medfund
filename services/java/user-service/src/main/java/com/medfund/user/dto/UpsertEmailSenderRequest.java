package com.medfund.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpsertEmailSenderRequest(
        @NotBlank @Email @Size(max = 255) String address,
        @Size(max = 255) String displayName,
        String notes
) {}
