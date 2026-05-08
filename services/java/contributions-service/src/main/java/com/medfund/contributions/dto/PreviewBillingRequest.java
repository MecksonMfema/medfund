package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wizard step 1 input — selects the population to bill.
 *
 * <p>Each filter list is OR'd within itself and AND'd across lists. Empty /
 * null filters mean "no constraint on that dimension" (e.g. omit
 * {@code groupIds} to include every group).
 */
public record PreviewBillingRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        List<UUID> schemeIds,
        List<UUID> groupIds,
        List<UUID> memberIds
) {}
