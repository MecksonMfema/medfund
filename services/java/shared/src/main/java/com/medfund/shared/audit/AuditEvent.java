package com.medfund.shared.audit;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEvent(
        UUID id,
        String tenantId,
        String entityType,
        String entityId,
        String entityName,
        String action,
        String actorId,
        String actorEmail,
        Map<String, Object> oldValue,
        Map<String, Object> newValue,
        String[] changedFields,
        String correlationId,
        Instant timestamp
) {
    /**
     * Canonical factory. Both {@code entityName} and {@code actorEmail} are
     * required (non-null) — audit listings need human-readable identifiers
     * on both axes so viewers don't have to join back to the source tables
     * just to know who did what to which entity. The earlier 10-arg overload
     * that defaulted {@code entityName = entityId} was removed in 2026-06
     * after a sweep showed every caller had silently been storing the UUID
     * twice; see {@code .claude/coding-standards.md} → "Entity identity on
     * audit events" for the convention.
     */
    public static AuditEvent create(
            String tenantId, String entityType, String entityId, String entityName,
            String action, String actorId, String actorEmail,
            Map<String, Object> oldValue, Map<String, Object> newValue,
            String[] changedFields, String correlationId) {
        return new AuditEvent(
                UUID.randomUUID(), tenantId, entityType, entityId, entityName,
                action, actorId, actorEmail, oldValue, newValue,
                changedFields, correlationId, Instant.now()
        );
    }
}
