package com.medfund.contributions.premium.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Parses the JSON body of a {@code medfund.user.policy-endorsed} record
 * into a {@link PolicyEndorsedPayload}. Mirrors {@link PolicyIssuedPayloadParser}
 * — every field is a JSON string; empty strings map to {@code null}.
 */
@Component
@RequiredArgsConstructor
public class PolicyEndorsedPayloadParser {

    private final ObjectMapper objectMapper;

    public PolicyEndorsedPayload parse(String json) throws IOException {
        JsonNode node = objectMapper.readTree(json);
        return new PolicyEndorsedPayload(
                text(node, "tenantId"),
                uuid(node, "endorsementId"),
                text(node, "reference"),
                uuid(node, "policyId"),
                text(node, "policySource"),
                text(node, "insuranceLine"),
                text(node, "changeType"),
                date(node, "effectiveFrom"),
                decimal(node, "premiumDelta"),
                text(node, "currencyCode"),
                instant(node, "committedAt"));
    }

    private static String text(JsonNode node, String field) {
        if (!node.has(field)) return null;
        String v = node.get(field).asText();
        return v == null || v.isBlank() ? null : v;
    }

    private static UUID uuid(JsonNode node, String field) {
        String v = text(node, field);
        if (v == null) return null;
        try { return UUID.fromString(v); } catch (IllegalArgumentException e) { return null; }
    }

    private static BigDecimal decimal(JsonNode node, String field) {
        String v = text(node, field);
        return v == null ? null : new BigDecimal(v);
    }

    private static LocalDate date(JsonNode node, String field) {
        String v = text(node, field);
        return v == null ? null : LocalDate.parse(v);
    }

    private static Instant instant(JsonNode node, String field) {
        String v = text(node, field);
        return v == null ? null : Instant.parse(v);
    }
}
