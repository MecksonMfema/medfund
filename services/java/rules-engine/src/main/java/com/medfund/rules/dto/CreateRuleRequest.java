package com.medfund.rules.dto;

import com.medfund.rules.model.RuleDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request body for creating a new rule. Either {@code definition} alone or
 * {@code drlOverride} alone is required — both is allowed (override wins at
 * compile time but the structured definition is preserved so the visual
 * editor can still render the original intent).
 */
public record CreateRuleRequest(
    @NotBlank @Size(max = 150) String ruleKey,
    @NotBlank @Size(max = 200) String name,
    String description,
    @NotBlank @Size(max = 50)  String category,
    String templateId,
    Integer priority,
    @NotNull RuleDefinition definition,
    String drlOverride,
    Boolean enabled
) {}
