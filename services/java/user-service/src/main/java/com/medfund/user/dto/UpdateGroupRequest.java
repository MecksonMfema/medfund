package com.medfund.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record UpdateGroupRequest(
    @Size(max = 200)
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

    /**
     * Liaison discriminator. Use the literal string "CLEAR" to drop the
     * existing liaison; null means "no change". Otherwise must be
     * 'MEMBER' or 'STAFF' and paired with {@link #liaisonUserId}.
     */
    @Pattern(regexp = "^(MEMBER|STAFF|CLEAR)$",
        message = "liaisonKind must be MEMBER, STAFF, or CLEAR")
    String liaisonKind,

    UUID liaisonUserId
) {}
