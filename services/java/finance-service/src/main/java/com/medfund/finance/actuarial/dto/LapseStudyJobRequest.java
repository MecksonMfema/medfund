package com.medfund.finance.actuarial.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/reports/actuarial/lapse-study}.
 * Mirrors {@link PersistencyStudyJobRequest} — both studies read the same
 * user-service persistency-cohort feed and both fall back to the plan's
 * default checkpoint trio (3, 6, 12, 24, 36 months) when the client omits
 * {@code checkpoints}.
 *
 * <p>{@code insuranceLine} null = every line the tenant has cohorts + basis
 * for. {@code reportingCurrency} is captured for the audit trail even
 * though the lapse rate is unit-less; kept for parity with the sibling
 * PERSISTENCY_STUDY request shape.
 */
public record LapseStudyJobRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        List<Integer> checkpoints,
        String insuranceLine,
        String reportingCurrency) {}
