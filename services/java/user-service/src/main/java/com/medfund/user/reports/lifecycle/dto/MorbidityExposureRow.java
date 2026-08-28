package com.medfund.user.reports.lifecycle.dto;

/**
 * Phase 14 §Actuarial Phase 14 — one row per (age_band, sex) aggregation
 * of the member-exposure feed for the MORBIDITY_STUDY report. Feeds
 * finance-service's {@code MorbidityExposureShapingService} which pivots
 * this into the exposure payload slot on {@code ActuarialJobRequestedEvent}.
 *
 * <p>Same population + exposure window as
 * {@link MortalityExposureRow}, but the numerator is
 * {@code incidents} instead of {@code deaths}. An "incident" is a
 * morbidity-onset transition on {@code member_status_history} (reason
 * codes matching the morbidity vocabulary — {@code illness_onset},
 * {@code disability_onset}, {@code hospitalization}) that landed inside
 * the window. Absent vocabulary entries return {@code 0} — the compute
 * emits an empty envelope with a warning per the Phase-13 "may be empty
 * in early dev" acceptance rather than a hard failure.
 */
public record MorbidityExposureRow(
        String ageBand,
        String sex,
        double exposureYears,
        long incidents
) {}
