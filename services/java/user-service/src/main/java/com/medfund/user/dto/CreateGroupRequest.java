package com.medfund.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateGroupRequest(
    @NotBlank @Size(max = 200)
    String name,

    @Size(max = 100)
    String registrationNumber,

    String address,

    /**
     * Discriminator for {@link #liaisonUserId}. Required — a group must
     * have a liaison so invoices, dunning, and the group-portal login
     * have a contact path.
     */
    @NotBlank
    @Pattern(regexp = "^(MEMBER|STAFF|LIAISON)$",
        message = "liaisonKind must be MEMBER, STAFF, or LIAISON")
    String liaisonKind,

    /** FK target — id in members, staff_users, or group_liaisons per {@link #liaisonKind}. */
    @NotNull
    UUID liaisonUserId
) {}
