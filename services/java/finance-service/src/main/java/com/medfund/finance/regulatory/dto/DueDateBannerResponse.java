package com.medfund.finance.regulatory.dto;

import java.time.LocalDate;

/**
 * One row per Phase 16 regulator report applicable to the calling tenant.
 * Feeds the Angular reports-hub due-date banner (Phase 6).
 *
 * <p>{@code submissionStatus} is one of {@code PENDING}, {@code SUBMITTED},
 * {@code AMENDED}, {@code SUPERSEDED} — the latter three are only surfaced
 * when the most recent submission for the {@code (periodStart)} slot carries
 * that status (a SUPERSEDED without a newer SUBMITTED would be a data bug).
 * {@code severity} is derived from {@code daysUntilDue}: {@code INFO}
 * when &gt;7d, {@code AMBER} when &le;7d, {@code RED} when &le;0d (overdue).
 */
public record DueDateBannerResponse(
        String reportKey,
        String reportLabel,
        String cadence,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate dueDate,
        long daysUntilDue,
        String submissionStatus,
        String severity
) {}
