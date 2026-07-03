package com.medfund.user.dto;

import com.medfund.user.entity.Group;

import java.time.Instant;
import java.util.UUID;

public record GroupResponse(
    UUID id,
    String name,
    String registrationNumber,
    String address,
    String email,
    String liaisonKind,
    UUID liaisonUserId,
    String status,
    Instant createdAt,
    Instant updatedAt
) {
    public static GroupResponse from(Group g) {
        return new GroupResponse(
            g.getId(), g.getName(), g.getRegistrationNumber(),
            g.getAddress(), g.getEmail(), g.getLiaisonKind(), g.getLiaisonUserId(),
            g.getStatus(), g.getCreatedAt(), g.getUpdatedAt()
        );
    }
}
