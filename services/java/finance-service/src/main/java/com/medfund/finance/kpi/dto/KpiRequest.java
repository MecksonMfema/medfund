package com.medfund.finance.kpi.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Composer input shared by every KPI compute path. {@code reportingCurrency}
 * is nullable → resolves to the tenant default via
 * {@link com.medfund.shared.report.ReportingCurrencyResolver}. The three K13
 * filter chips ({@code insuranceLine}, {@code schemeId}, {@code producerId})
 * are each nullable — a null chip means "no filter on that dimension".
 */
public record KpiRequest(
        UUID tenantId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String reportingCurrency,
        String insuranceLine,
        UUID schemeId,
        UUID producerId) {}
