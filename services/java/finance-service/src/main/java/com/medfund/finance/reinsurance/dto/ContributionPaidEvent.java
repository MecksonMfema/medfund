package com.medfund.finance.reinsurance.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Reinsurance-side view of the {@code medfund.contributions.paid} payload
 * produced by {@code ContributionEventPublisher.publishContributionPaid}.
 * Only the fields the premium-cession consumer needs are extracted.
 *
 * <p>{@code insuranceLine}, {@code currencyCode} and {@code paidAt} were
 * added to the publisher payload in Phase 6 — the pre-Phase-6 payload
 * carried only {contributionId, memberId, amount}. Consumers that predate
 * this deploy simply ignore the new fields, so the change is additive
 * per the parent-plan {@code :3005} invariant. Missing / blank fields
 * fall through to safe defaults so a partial deploy does not break the
 * consumer — the caller filters events with a null line or non-positive
 * amount out at the service layer.
 */
public record ContributionPaidEvent(
        UUID contributionId,
        UUID memberId,
        String insuranceLine,
        BigDecimal amount,
        String currencyCode,
        OffsetDateTime paidAt,
        String tenantId
) {

    public static ContributionPaidEvent from(JsonNode node) {
        return new ContributionPaidEvent(
                parseUuid(text(node, "contributionId")),
                parseUuid(text(node, "memberId")),
                text(node, "insuranceLine"),
                parseAmount(text(node, "amount")),
                orDefault(text(node, "currencyCode"), "USD"),
                parseTimestamp(text(node, "paidAt")),
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

    private static OffsetDateTime parseTimestamp(String s) {
        if (s == null || s.isBlank()) return OffsetDateTime.now();
        try {
            return OffsetDateTime.parse(s);
        } catch (java.time.format.DateTimeParseException e) {
            return OffsetDateTime.now();
        }
    }

    private static String orDefault(String s, String fallback) {
        return (s == null || s.isBlank()) ? fallback : s;
    }
}
