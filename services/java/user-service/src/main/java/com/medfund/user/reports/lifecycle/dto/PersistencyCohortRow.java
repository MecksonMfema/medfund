package com.medfund.user.reports.lifecycle.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Phase 13 §C Phase 8 per L9 + L16 — one row per (cohort_period,
 * insurance_line, checkpoint_months). {@code cohortSize} is the count of
 * newly-active policies (or members for HEALTH) starting in the cohort
 * period; {@code stillActive} is how many of them are still active
 * {@code checkpointMonths} later; {@code retentionRate} is a percentage
 * for the workbook to render.
 *
 * <p>HEALTH uses the strict definition (still active + has a
 * Contribution in the checkpoint month per L16); annual lines use
 * renewal-chain-active per L9.
 */
public record PersistencyCohortRow(
    LocalDate cohortMonth,
    String    insuranceLine,
    int       checkpointMonths,
    long      cohortSize,
    long      stillActive,
    BigDecimal retentionRate
) {}
