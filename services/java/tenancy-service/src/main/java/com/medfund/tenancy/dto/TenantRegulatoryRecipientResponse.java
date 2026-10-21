package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantRegulatoryRecipient;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record TenantRegulatoryRecipientResponse(
        UUID id,
        UUID tenantId,
        String email,
        String displayName,
        List<String> subscribedEventTiers,
        Boolean isActive,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        String actorEmail
) {
    public static TenantRegulatoryRecipientResponse from(TenantRegulatoryRecipient row) {
        String[] tiers = row.getSubscribedEventTiers();
        return new TenantRegulatoryRecipientResponse(
                row.getId(),
                row.getTenantId(),
                row.getEmail(),
                row.getDisplayName(),
                tiers != null ? List.of(tiers) : List.of(),
                row.getIsActive(),
                row.getCreatedAt(),
                row.getUpdatedAt(),
                row.getActorEmail()
        );
    }
}
