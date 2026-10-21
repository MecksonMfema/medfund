package com.medfund.finance.producer.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Commission-side view of the {@code medfund.users.member-lifecycle} payload
 * published by {@code UserEventPublisher.publishMemberLifecycle} in
 * user-service. Only carries the fields the
 * {@code CommissionClawbackConsumer} needs: the member id, the new status,
 * an optional termination/lapse date to timestamp the clawback, the tenant
 * id for context propagation, and the free-text reason for the audit trail.
 *
 * <p>Terminal statuses that trigger commission clawback: {@code lapsed},
 * {@code terminated}, {@code deactivated}. All other statuses (e.g.
 * {@code active}, {@code suspended}) are filtered by the consumer before
 * this DTO is even constructed.
 */
public record MemberLifecycleEvent(
        String event,
        UUID memberId,
        String status,
        String reason,
        LocalDate terminationDate,
        UUID groupId,
        UUID schemeId,
        String tenantId
) {

    /** Best-effort event instant — uses terminationDate at start of day if
     *  provided, else falls back to {@code Instant.now()} so downstream
     *  arithmetic (clawback window comparisons) has something to work with. */
    public Instant eventInstant() {
        if (terminationDate != null) {
            return terminationDate.atStartOfDay().toInstant(java.time.ZoneOffset.UTC);
        }
        return Instant.now();
    }

    public static MemberLifecycleEvent from(JsonNode node) {
        return new MemberLifecycleEvent(
                text(node, "event"),
                parseUuid(text(node, "memberId")),
                text(node, "status"),
                text(node, "reason"),
                parseDate(text(node, "terminationDate")),
                parseUuid(text(node, "groupId")),
                parseUuid(text(node, "schemeId")),
                text(node, "tenantId")
        );
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return (v == null || v.isBlank() || "null".equals(v)) ? null : v;
    }

    private static UUID parseUuid(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return UUID.fromString(s);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return LocalDate.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            return null;
        }
    }
}
