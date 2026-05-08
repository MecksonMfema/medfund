package com.medfund.rules.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Read-only template descriptor returned by {@code GET /rule-templates}.
 * The frontend uses these to seed the New Rule modal so users can start
 * from a known-good baseline rather than from a blank canvas.
 */
public record RuleTemplateResponse(
    String id,
    String name,
    String description,
    String category,
    Integer priority,
    JsonNode definition
) {}
