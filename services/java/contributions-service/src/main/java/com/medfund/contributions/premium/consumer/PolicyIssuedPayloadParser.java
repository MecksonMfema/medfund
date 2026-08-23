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
 * Parses the JSON body of a {@code medfund.user.policy-issued} record into
 * a {@link PolicyIssuedPayload}. Every field on the wire is a string —
 * empty strings map to null on the way out (matching the publisher's
 * "serialize null → empty string" convention).
 */
@Component
@RequiredArgsConstructor
public class PolicyIssuedPayloadParser {

    private final ObjectMapper objectMapper;

    public PolicyIssuedPayload parse(String json) throws IOException {
        JsonNode node = objectMapper.readTree(json);
        return new PolicyIssuedPayload(
                text(node, "tenantId"),
                uuid(node, "policyId"),
                text(node, "policyNumber"),
                text(node, "policySource"),
                text(node, "insuranceLine"),
                decimal(node, "writtenPremium"),
                text(node, "currencyCode"),
                date(node, "coverageStart"),
                date(node, "coverageEnd"),
                instant(node, "boundAt"),
                uuid(node, "memberId"),
                uuid(node, "portfolioId"),
                uuid(node, "cohortId"),
                uuid(node, "renewedFromPolicyId"));
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
