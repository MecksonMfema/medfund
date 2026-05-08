package com.medfund.rules.dto;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Inputs for the dry-run sandbox. Each field is the JSON form of the
 * corresponding fact type the rule engine knows about. Any field can be
 * null — only the facts the rule under test references need to be provided.
 *
 * <p>The rule engine always inserts a {@code ClaimFact} into its session
 * (legacy claims rules expect {@code $claim} bound), so {@code claim} is
 * silently treated as an empty object when omitted; non-claim rules that
 * never read claim fields don't need to populate it.
 */
public record DryRunRequest(
    JsonNode claim,
    JsonNode member,
    JsonNode dependant,
    JsonNode provider,
    JsonNode family,
    JsonNode lifecycle,
    JsonNode contribution,
    JsonNode paymentRun,
    JsonNode time
) {}
