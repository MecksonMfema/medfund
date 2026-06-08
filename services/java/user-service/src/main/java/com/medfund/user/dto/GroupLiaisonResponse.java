package com.medfund.user.dto;

import com.medfund.user.entity.GroupLiaison;

import java.time.Instant;
import java.util.UUID;

public record GroupLiaisonResponse(
    UUID id,
    String firstName,
    String lastName,
    String email,
    String phone,
    String address,
    String status,
    Instant createdAt,
    Instant updatedAt
) {
    public static GroupLiaisonResponse from(GroupLiaison l) {
        return new GroupLiaisonResponse(
            l.getId(), l.getFirstName(), l.getLastName(),
            l.getEmail(), l.getPhone(), l.getAddress(),
            l.getStatus(), l.getCreatedAt(), l.getUpdatedAt()
        );
    }
}
