package com.medfund.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Updates the editable metadata of an existing role. The role's {@code name}
 * is intentionally NOT in this DTO — it's the stable identifier used by user
 * tooling and (eventually) Keycloak realm-role mirroring, so renaming it would
 * orphan existing references. Display name and description are cosmetic and
 * safe to change.
 */
public record UpdateRoleRequest(
        @NotBlank @Size(max = 200)
        String displayName,

        String description
) {}
