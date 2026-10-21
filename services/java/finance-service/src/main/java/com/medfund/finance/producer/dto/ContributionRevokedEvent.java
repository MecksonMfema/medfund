package com.medfund.finance.producer.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Commission-side view of the {@code medfund.contributions.revoked} payload
 * published by {@code ContributionEventPublisher.publishContributionRevoked}.
 * Fired once per revoked contribution — batch revokes fire N events, one
 * per contribution.
 *
 * <p>Missing / blank string fields fall through to null; missing amount to
 * zero; missing timestamp to {@code now()}. The {@code CommissionClawbackService}
 * short-circuits on a null contributionId or blank tenant so a partial deploy
 * (contributions-service ahead of finance-service) doesn't crash the
 * consumer.
 */
public record ContributionRevokedEvent(
        UUID contributionId,
        UUID invoiceId,
        UUID memberId,
        UUID groupId,
        BigDecimal amount,
        String currencyCode,
        String insuranceLine,
        String tenantId,
        String actorId,
        String actorEmail,
        OffsetDateTime revokedAt
) {

    public static ContributionRevokedEvent from(JsonNode node) {
        return new ContributionRevokedEvent(
                parseUuid(text(node, "contributionId")),
                parseUuid(text(node, "invoiceId")),
                parseUuid(text(node, "memberId")),
                parseUuid(text(node, "groupId")),
                parseAmount(text(node, "amount")),
                orDefault(text(node, "currencyCode"), "USD"),
                text(node, "insuranceLine"),
                text(node, "tenantId"),
                orDefault(text(node, "actorId"), "system"),
                orDefault(text(node, "actorEmail"), "system@medfund"),
                parseTimestamp(text(node, "revokedAt"))
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
