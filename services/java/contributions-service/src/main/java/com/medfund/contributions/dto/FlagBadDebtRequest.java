package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Request to persist a bad-debt row from the operational page. The user
 * picks an aged contribution and clicks "Flag as bad debt" — the existing
 * {@link com.medfund.contributions.service.BadDebtService#flagAsOverdue}
 * state machine handles the rest.
 */
public record FlagBadDebtRequest(
        @NotNull UUID contributionId,
        String reason
) {}
