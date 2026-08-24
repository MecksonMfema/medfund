package com.medfund.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * Phase 13 §A per L1 + L17. Single-field body for the inline dropdown —
 * intentionally narrow so the admin edit path can't accidentally rewrite
 * unrelated provider columns.
 */
public record UpdateProviderNetworkTierRequest(
    @NotNull
    @Pattern(regexp = "STANDARD|TIER_1|TIER_2|TIER_3",
             message = "networkTier must be STANDARD, TIER_1, TIER_2 or TIER_3")
    String networkTier
) {}
