package com.medfund.finance.actuarial.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.List;

/**
 * Request body for {@code POST /api/v1/reports/actuarial/persistency-study}.
 * {@code checkpoints} null/empty falls back to the plan's default trio
 * (3, 6, 12, 24, 36 months on the tenant-admin form; the study service
 * inherits the user-service's server-side default when the client omits it).
 *
 * <p>{@code insuranceLine} null = every line the tenant has cohorts + basis for.
 * {@code reportingCurrency} is captured for the audit trail even though the
 * persistency study is unit-less (retention %); leaving room for a currency-
 * denominated derived metric (e.g. AAV) in a follow-up.
 */
public record PersistencyStudyJobRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        List<Integer> checkpoints,
        String insuranceLine,
        String reportingCurrency) {}
