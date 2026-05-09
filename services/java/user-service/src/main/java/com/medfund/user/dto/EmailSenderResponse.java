package com.medfund.user.dto;

import com.medfund.user.entity.EmailSender;

import java.time.Instant;
import java.util.UUID;

public record EmailSenderResponse(
        UUID id,
        String address,
        String displayName,
        String status,
        Instant verifiedAt,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static EmailSenderResponse from(EmailSender s) {
        return new EmailSenderResponse(
                s.getId(), s.getAddress(), s.getDisplayName(), s.getStatus(),
                s.getVerifiedAt(), s.getNotes(), s.getCreatedAt(), s.getUpdatedAt());
    }
}
