package com.medfund.rules.dto;

import com.medfund.rules.fact.RuleResult;

import java.util.List;
import java.util.UUID;

/**
 * Outcome of a dry-run. {@code matched} captures every rule that fired
 * during the evaluation (typically one — the single rule under test —
 * unless the tenant's other rules are loaded too). {@code executionMs} is
 * a rough wall-clock figure for the editor to surface back to the author.
 */
public record DryRunResponse(
    UUID ruleId,
    String decision,
    List<RuleResult> matched,
    long executionMs
) {}
