package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wizard step 1 input — selects the population to bill.
 *
 * <p>Filters are AND'd together. Empty / null filters mean "no constraint on
 * that dimension" (e.g. omit {@code groupIds} to include every group). Period
 * alone bills every active scheme.
 *
 * <p>{@code insuranceLine} is the wizard's tab choice for multi-line tenants
 * (Part 4.5). Defaults to HEALTH when omitted so the single-line tenants
 * that have been running this wizard since v1 see no behaviour change. The
 * backend routes by this value to the matching {@code CandidateResolver}.
 */
public record PreviewBillingRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        List<UUID> groupIds,
        List<UUID> memberIds,
        String insuranceLine
) {}
