package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public record CreateCessionRuleRequest(
        @NotNull UUID ruleDefinitionId,
        Boolean enabled
) {}
