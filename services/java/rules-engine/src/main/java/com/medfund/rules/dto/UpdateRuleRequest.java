package com.medfund.rules.dto;

import com.medfund.rules.model.RuleDefinition;

/**
 * Partial update — every field is optional. Null fields are left untouched
 * on the existing row. The {@code ruleKey} is intentionally not editable
 * because the index/unique constraint depends on it; rename via delete+create.
 */
public record UpdateRuleRequest(
    String name,
    String description,
    String category,
    Integer priority,
    RuleDefinition definition,
    String drlOverride,
    Boolean enabled
) {}
