package com.medfund.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateGroupRequest(
    @NotBlank @Size(max = 200)
    String name,

    @Size(max = 100)
    String registrationNumber,

    @Size(max = 200)
    String contactPerson,

    @Email @Size(max = 255)
    String contactEmail,

    @Size(max = 50)
    String contactPhone,

    String address,

    /** Discriminator for {@link #liaisonUserId}: 'MEMBER' or 'STAFF'. */
    @Pattern(regexp = "^(MEMBER|STAFF)$",
        message = "liaisonKind must be either MEMBER or STAFF")
    String liaisonKind,

    /** FK target — id in members or staff_users, per {@link #liaisonKind}. */
    UUID liaisonUserId
) {}
