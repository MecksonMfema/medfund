package com.medfund.user.dto;

import com.medfund.user.entity.StaffUser;
import com.medfund.user.service.StaffUserService;

import java.time.Instant;
import java.util.UUID;

public record StaffUserResponse(
    UUID id,
    String firstName,
    String lastName,
    String email,
    String phone,
    String jobTitle,
    String department,
    String realmRole,
    String keycloakUserId,
    UUID tenantId,
    String status,
    Instant invitedAt,
    Instant inviteExpiresAt,
    boolean inviteAccepted,
    Instant createdAt,
    Instant updatedAt
) {
    public static StaffUserResponse from(StaffUser u) {
        Instant invitedAt = u.getInvitedAt();
        Instant expiresAt = invitedAt != null ? invitedAt.plus(StaffUserService.INVITE_TTL) : null;
        boolean accepted = !"invited".equalsIgnoreCase(u.getStatus());
        return new StaffUserResponse(
            u.getId(),
            u.getFirstName(),
            u.getLastName(),
            u.getEmail(),
            u.getPhone(),
            u.getJobTitle(),
            u.getDepartment(),
            u.getRealmRole(),
            u.getKeycloakUserId(),
            u.getTenantId(),
            u.getStatus(),
            invitedAt,
            expiresAt,
            accepted,
            u.getCreatedAt(),
            u.getUpdatedAt()
        );
    }
}
