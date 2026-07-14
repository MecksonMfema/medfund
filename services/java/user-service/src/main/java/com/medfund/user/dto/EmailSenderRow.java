package com.medfund.user.dto;

import java.time.Instant;
import java.util.UUID;

public record EmailSenderRow(
        UUID id,
        String address,
        String displayName,
        String status,
        Instant verifiedAt,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
}
