package com.medfund.finance.producer.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Commission-side view of the {@code medfund.contributions.paid} payload
 * published by {@code ContributionEventPublisher.publishContributionPaid}
 * in contributions-service. Only the fields the {@code CommissionCalcService}
 * needs are extracted.
 *
 * <p>Blank / missing values fall through to safe defaults so a partial deploy
 * doesn't break the consumer — the service layer filters events with a null
 * contributionId, tenant or non-positive amount before doing any work.
 *
 * <p>Kept as a producer-package parallel to
 * {@code com.medfund.finance.reinsurance.dto.ContributionPaidEvent} so the
 * two subpackages stay independently evolvable — reinsurance rules on the
 * {@code claim.amount} slot; commission rules key off the producer id
 * and rate card.
 */
public record ContributionPaidEvent(
        UUID contributionId,
        UUID memberId,
        BigDecimal amount,
        String currencyCode,
        String insuranceLine,
        OffsetDateTime paidAt,
        String tenantId
) {

    public static ContributionPaidEvent from(JsonNode node) {
        return new ContributionPaidEvent(
                parseUuid(text(node, "contributionId")),
                parseUuid(text(node, "memberId")),
                parseAmount(text(node, "amount")),
                orDefault(text(node, "currencyCode"), "USD"),
                text(node, "insuranceLine"),
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
