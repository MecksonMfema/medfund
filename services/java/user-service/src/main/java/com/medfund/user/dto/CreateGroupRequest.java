package com.medfund.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateGroupRequest(
    @NotBlank @Size(max = 200)
    String name,

    @Size(max = 100)
    String registrationNumber,

    String address,

    /** Discriminator for {@link #liaisonUserId}: 'MEMBER', 'STAFF', or 'LIAISON'. */
    @Pattern(regexp = "^(MEMBER|STAFF|LIAISON)$",
        message = "liaisonKind must be MEMBER, STAFF, or LIAISON")
    String liaisonKind,

    /** FK target — id in members, staff_users, or group_liaisons per {@link #liaisonKind}. */
    UUID liaisonUserId
) {}
