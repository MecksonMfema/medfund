package com.medfund.contributions.premium.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Parses the JSON body of a {@code medfund.user.policy-status-changed}
 * record into a {@link PolicyStatusChangedPayload}. Mirrors
 * {@link PolicyEndorsedPayloadParser} — every field is a JSON string;
 * empty strings map to {@code null}.
 */
@Component
@RequiredArgsConstructor
public class PolicyStatusChangedPayloadParser {

    private final ObjectMapper objectMapper;

    public PolicyStatusChangedPayload parse(String json) throws IOException {
        JsonNode node = objectMapper.readTree(json);
        return new PolicyStatusChangedPayload(
                text(node, "tenantId"),
                uuid(node, "policyId"),
                text(node, "policySource"),
                text(node, "insuranceLine"),
                text(node, "fromStatus"),
                text(node, "toStatus"),
                offsetDateTime(node, "effectiveAt"),
                text(node, "reasonCode"),
                text(node, "actorId"),
                text(node, "actorEmail"));
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

    private static OffsetDateTime offsetDateTime(JsonNode node, String field) {
        String v = text(node, field);
        return v == null ? null : OffsetDateTime.parse(v);
    }
}
