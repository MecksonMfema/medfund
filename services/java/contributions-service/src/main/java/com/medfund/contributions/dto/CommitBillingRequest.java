package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wizard step 3 input — same filter shape as the preview. The server re-runs
 * the same selection and persists the resulting contribution rows. The cooldown
 * configured in {@code billing_cycle_config} guards against accidental double
 * commits on refresh.
 *
 * <p>{@code insuranceLine} mirrors the preview request — see
 * {@link PreviewBillingRequest} for the routing semantics.
 */
public record CommitBillingRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        List<UUID> groupIds,
        List<UUID> memberIds,
        String insuranceLine
) {}
