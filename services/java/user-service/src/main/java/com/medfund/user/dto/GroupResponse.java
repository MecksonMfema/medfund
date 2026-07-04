package com.medfund.user.dto;

import com.medfund.user.entity.Group;

import java.time.Instant;
import java.time.LocalDate;
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
    /** Non-null when the group has a future-dated status change queued
     *  (V042 scheduled trio). Cleared when the SCHEDULED_STATUS_ROLL
     *  job applies it. */
    String scheduledStatus,
    LocalDate scheduledStatusEffectiveFrom,
    String scheduledStatusReason,
    Instant createdAt,
    Instant updatedAt
) {
    public static GroupResponse from(Group g) {
        return new GroupResponse(
            g.getId(), g.getName(), g.getRegistrationNumber(),
            g.getAddress(), g.getEmail(), g.getLiaisonKind(), g.getLiaisonUserId(),
            g.getStatus(),
            g.getScheduledStatus(),
            g.getScheduledStatusEffectiveFrom(),
            g.getScheduledStatusReason(),
            g.getCreatedAt(), g.getUpdatedAt()
        );
    }
}
