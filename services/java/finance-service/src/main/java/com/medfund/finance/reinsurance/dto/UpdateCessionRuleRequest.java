package com.medfund.finance.reinsurance.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateCessionRuleRequest(
        @NotNull Boolean enabled
) {}
