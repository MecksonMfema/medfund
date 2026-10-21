package com.medfund.shared.security;

/**
 * Payload published to the {@code medfund.security.events} Kafka topic.
 * Field names match the Go {@code SecurityEvent} struct JSON tags so the
 * audit service consumer can unmarshal without any mapping layer.
 *
 * <p>Structurally identical to {@code com.medfund.keycloak.SecurityEventMessage}
 * in the keycloak-event-listener module (which lives in its own gradle root and
 * therefore cannot depend on {@code shared}). Both write the same wire shape
 * to the same topic; the audit-service consumer deserialises both without
 * special-casing.
 */
public record SecurityEventMessage(
        String id,
        String tenantId,
        String eventType,
        String userId,
        String actorEmail,
        String ipAddress,
        String userAgent,
        String details,
        String timestamp
) {}
