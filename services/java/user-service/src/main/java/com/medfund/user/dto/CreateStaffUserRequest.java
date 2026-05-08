package com.medfund.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
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

    /**
     * Coarse Keycloak realm role for portal-section gating
     * (super_admin / tenant_admin / etc.). Kept for backward compatibility —
     * fine-grained authorisation now flows through {@link #roleIds()}
     * which writes user_roles rows in the tenant schema. See Phase 1.7 of
     * the operational-portal plan; the column drop is scheduled for Phase 5
     * once the new permission flow is fully in production.
     */
    @NotBlank
    String realmRole,

    /**
     * Tenant-defined DB roles to assign — each row goes into user_roles in
     * the tenant's schema. Resolved permissions = union across all roles.
     * Optional; an empty/null list means the user gets only what
     * {@code realmRole} grants via Keycloak (legacy behaviour).
     */
    List<UUID> roleIds,

    /** Required for all roles except super_admin. Null means platform-level user. */
    UUID tenantId
) {}
