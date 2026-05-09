package com.medfund.user.dto;

import com.medfund.user.entity.EmailCampaign;

import java.time.Instant;
import java.util.UUID;

public record EmailCampaignResponse(
        UUID id,
        UUID senderId,
        String subject,
        String bodyHtml,
        String bodyText,
        String audienceFilter,
        String status,
        Instant scheduledFor,
        Instant sentAt,
        Integer recipientCount,
        Instant createdAt,
        Instant updatedAt
) {
    public static EmailCampaignResponse from(EmailCampaign c) {
        return new EmailCampaignResponse(
                c.getId(), c.getSenderId(), c.getSubject(),
                c.getBodyHtml(), c.getBodyText(),
                c.getAudienceFilter() != null ? c.getAudienceFilter().asString() : "{}",
                c.getStatus(), c.getScheduledFor(), c.getSentAt(),
                c.getRecipientCount(), c.getCreatedAt(), c.getUpdatedAt());
    }
}
