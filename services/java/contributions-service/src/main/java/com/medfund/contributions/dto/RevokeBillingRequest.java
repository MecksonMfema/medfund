package com.medfund.contributions.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

/**
 * Revoke wizard input. Deletes every contribution + invoice for the
 * (period, line) so the operator can re-commit a corrected run.
 *
 * <p>Only revocable for the calendar month immediately following the
 * current month — the cut-off is enforced server-side in
 * {@code BillingService.revokeBilling} so a hand-edited payload can't
 * undo an already-active month. Lines outside this window need the
 * separate corrections flow (out of scope here).
 */
public record RevokeBillingRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        /** Null = HEALTH (legacy single-line behaviour). */
        String insuranceLine
) {}
