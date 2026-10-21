package com.medfund.tenancy.dto;

import com.medfund.tenancy.entity.TenantIfrs17NotificationConfig;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TenantIfrs17NotificationConfigResponse(
        UUID id,
        UUID tenantId,
        String eventType,
        String deliveryMethod,
        String recipient,
        Integer throttleMinutes,
        Boolean isActive,
        OffsetDateTime updatedAt,
        String updatedByEmail
) {
    public static TenantIfrs17NotificationConfigResponse from(TenantIfrs17NotificationConfig row) {
        return new TenantIfrs17NotificationConfigResponse(
                row.getId(),
                row.getTenantId(),
                row.getEventType(),
                row.getDeliveryMethod(),
                row.getRecipient(),
                row.getThrottleMinutes(),
                row.getIsActive(),
                row.getUpdatedAt(),
                row.getUpdatedByEmail()
        );
    }
}
