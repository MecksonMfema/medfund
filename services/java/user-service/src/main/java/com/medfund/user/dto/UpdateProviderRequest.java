package com.medfund.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record UpdateProviderRequest(
    @Size(max = 200)
    String name,

    @Size(max = 50)
    String providerType,

    @Size(max = 100)
    String registrationNumber,

    @Size(max = 100)
    String specialty,

    @Email @Size(max = 255)
    String email,

    @Size(max = 50)
    String phone,

    @Size(max = 100)
    String city,

    String address,

    String bankingDetails
) {}
