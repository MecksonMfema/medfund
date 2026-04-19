package com.medfund.keycloak;

/**
 * Payload published to the {@code medfund.security.events} Kafka topic.
 * Field names match the Go {@code SecurityEvent} struct JSON tags so the
 * audit service consumer can unmarshal without any mapping layer.
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
