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

    String address,

    /**
     * Group contact email. Optional on update (null = no change); when
     * provided must be a valid address. Matches the recipient resolver's
     * fallback source for statements when no liaison is assigned.
     */
    @Email @Size(max = 255)
    String email,

    /**
     * Liaison discriminator. "CLEAR" drops the existing liaison; null means
     * "no change". Otherwise must be 'MEMBER', 'STAFF', or 'LIAISON' paired
     * with {@link #liaisonUserId}.
     */
    @Pattern(regexp = "^(MEMBER|STAFF|LIAISON|CLEAR)$",
        message = "liaisonKind must be MEMBER, STAFF, LIAISON, or CLEAR")
    String liaisonKind,

    UUID liaisonUserId
) {}
