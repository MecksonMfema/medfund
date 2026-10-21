package com.medfund.finance.reinsurance.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Reinsurance-side view of the {@code medfund.claims.adjudicated} payload
 * produced by {@code ClaimEventPublisher.publishClaimAdjudicated}. Only
 * the fields the reinsurance consumer needs are extracted — the full
 * V077 7-bucket cost-share breakdown stays in the JSON node for consumers
 * that care.
 *
 * <p>{@code occurredAt} is filled from {@code adjudicatedAt} when present,
 * else {@link OffsetDateTime#now()} at parse time — the shape today
 * doesn't include a canonical adjudication timestamp, but we need one to
 * key the cession bordereau period.
 */
public record ClaimAdjudicatedEvent(
        UUID claimId,
        String claimNumber,
        UUID memberId,
        UUID providerId,
        String insuranceLine,
        String currencyCode,
        BigDecimal approvedAmount,
        String decision,
        String payeeType,
        OffsetDateTime occurredAt,
        String tenantId
) {

    public static ClaimAdjudicatedEvent from(JsonNode node) {
        return new ClaimAdjudicatedEvent(
                parseUuid(text(node, "claimId")),
                text(node, "claimNumber"),
                parseUuid(text(node, "memberId")),
                parseUuid(text(node, "providerId")),
                text(node, "insuranceLine"),
                orDefault(text(node, "currencyCode"), "USD"),
                parseAmount(text(node, "approvedAmount")),
                text(node, "decision"),
                text(node, "payeeType"),
                OffsetDateTime.now(),
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

    private static BigDecimal parseAmount(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException e) {
            return BigDecimal.ZERO;
        }
    }

    private static String orDefault(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
