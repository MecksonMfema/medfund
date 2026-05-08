package com.medfund.rules.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * Response envelope for a tenant rule. {@code definition} is exposed as a
 * {@link JsonNode} so the frontend receives the parsed JSON tree directly
 * — no nested string-of-JSON gymnastics in the visual editor.
 */
public record TenantRuleResponse(
    UUID id,
    UUID tenantId,
    String ruleKey,
    String name,
    String description,
    String category,
    String templateId,
    Integer priority,
    JsonNode definition,
    String drlOverride,
    Boolean enabled,
    Integer version,
    Instant createdAt,
    Instant updatedAt
) {}
