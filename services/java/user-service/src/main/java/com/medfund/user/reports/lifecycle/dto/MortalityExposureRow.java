package com.medfund.user.reports.lifecycle.dto;

/**
 * Phase 14 §Actuarial Phase 13 — one row per (age_band, sex) aggregation
 * of the member-exposure feed. Feeds finance-service's
 * {@code MortalityExposureShapingService} which pivots this into the
 * exposure payload slot on {@code ActuarialJobRequestedEvent}.
 *
 * <p>{@code ageBand} is a 5-year canonical bucket ("0-4", "5-9", …, "85+").
 * {@code sex} is "male" / "female" / "unknown" (rows with unknown sex are
 * kept in the feed but the mortality compute skips them with a warning).
 * {@code exposureYears} is the sum of person-years exposed to risk over
 * the requested window (enrollment to termination/death/window-end,
 * whichever comes first). {@code deaths} counts members whose
 * {@code death_date} lands in the window.
 */
public record MortalityExposureRow(
        String ageBand,
        String sex,
        double exposureYears,
        long deaths
) {}
