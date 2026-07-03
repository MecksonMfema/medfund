package com.medfund.shared.notification;

import java.time.Instant;
import java.util.UUID;

/**
 * Wire shape for {@code GET /api/v1/notifications}. Excludes internal
 * columns (metadata, tenant_id when caller isn't a super-admin) and
 * exposes {@code seen} as a boolean so the client doesn't have to
 * parse timestamps to render the badge.
 */
public record NotificationSummary(
        UUID id,
        String kind,
        String title,
        String body,
        String severity,
        String sourceType,
        UUID sourceId,
        String actionUrl,
        UUID tenantId,
        Instant createdAt,
        boolean seen
) {
    public static NotificationSummary from(Notification n) {
        return new NotificationSummary(
                n.getId(),
                n.getKind(),
                n.getTitle(),
                n.getBody(),
                n.getSeverity() == null ? NotificationSeverity.INFO : n.getSeverity(),
                n.getSourceType(),
                n.getSourceId(),
                n.getActionUrl(),
                n.getTenantId(),
                n.getCreatedAt(),
                n.getSeenAt() != null);
    }
}
