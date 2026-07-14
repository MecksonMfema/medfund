package com.medfund.user.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Row shape returned by the paginated email-campaigns list. Carries the
 * sender's address + display name inline so the list never renders a raw
 * senderId UUID.
 */
public record EmailCampaignRow(
        UUID id,
        UUID senderId,
        String senderAddress,
        String senderDisplayName,
        String subject,
        String status,
        Instant scheduledFor,
        Instant sentAt,
        Integer recipientCount,
        Instant createdAt,
        Instant updatedAt
) {
}
