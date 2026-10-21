package com.medfund.finance.actuarial.dto;

import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request body for {@code POST /api/v1/reports/actuarial/morbidity-study}.
 *
 * <p>{@code insuranceLine} null defaults to HEALTH — every tenant has
 * that source; other lines need an active {@code tenant_morbidity_basis}
 * row per line to compute expected morbidity. {@code basisNameOverride}
 * lets an actuary probe an alternative basis without editing the tenant
 * configuration; {@code multiplierOverride} nudges the basis for what-if
 * runs. {@code reportingCurrency} is captured for the audit trail even
 * though morbidity is unit-less; kept for parity with the sibling
 * study request shapes.
 */
public record MorbidityStudyJobRequest(
        @NotNull LocalDate periodStart,
        @NotNull LocalDate periodEnd,
        String insuranceLine,
        String basisNameOverride,
        BigDecimal multiplierOverride,
        String reportingCurrency) {}
