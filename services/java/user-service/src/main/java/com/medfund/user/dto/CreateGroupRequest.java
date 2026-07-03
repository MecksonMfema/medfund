package com.medfund.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * Group creation payload. Field-level validation only checks shape;
 * the semantic rule "email OR liaison must be set" is enforced in
 * {@code GroupService.create} so we can produce a single friendly
 * 422 that names both alternatives.
 */
public record CreateGroupRequest(
    @NotBlank @Size(max = 200)
    String name,

    @Size(max = 100)
    String registrationNumber,

    String address,

    /** Group contact email — optional if a liaison is assigned. */
    @Email @Size(max = 255)
    String email,

    /** Optional — pairs with {@link #liaisonUserId}. */
    @Pattern(regexp = "^(MEMBER|STAFF|LIAISON)$",
        message = "liaisonKind must be MEMBER, STAFF, or LIAISON")
    String liaisonKind,

    /** FK target — id in members, staff_users, or group_liaisons per {@link #liaisonKind}. */
    UUID liaisonUserId
) {}
