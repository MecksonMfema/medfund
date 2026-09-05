package com.medfund.finance.kpi.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * K12/K6: composite scalar in the reporting currency + basis note + the
 * per-currency native breakdown. Basis note is {@code "MIXED_LOSS_EARNED_EXPENSE_WRITTEN"}
 * for COMBINED_RATIO (K6 mixed-basis footnote) and {@code null} for the four
 * single-basis KPIs.
 *
 * <p>{@code perCurrency} is embedded here (rather than pushed up to
 * {@link com.medfund.shared.report.ReportResponse#perCurrency()}) because the
 * envelope's {@code perCurrency} shape uses {@link com.medfund.shared.report.PerCurrencyTotal}
 * — a single-amount+count row — which cannot carry a ratio/numerator/denominator
 * triple. The KPI tile UI reads {@code data.perCurrency} directly.
 */
public record KpiReportData(
        BigDecimal compositeRatio,
        BigDecimal compositeNumerator,
        BigDecimal compositeDenominator,
        String basisNote,
        Map<String, KpiValue> perCurrency) {}
