package com.medfund.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateStaffUserRequest(
    @NotBlank @Size(max = 100)
    String firstName,

    @NotBlank @Size(max = 100)
    String lastName,

    @NotBlank @Email @Size(max = 255)
    String email,

    @Size(max = 50)
    String phone,

    @Size(max = 100)
    String jobTitle,

    @Size(max = 100)
    String department,

    @NotBlank
    String realmRole,

    /** Required for all roles except super_admin. Null means platform-level user. */
    UUID tenantId
) {}
