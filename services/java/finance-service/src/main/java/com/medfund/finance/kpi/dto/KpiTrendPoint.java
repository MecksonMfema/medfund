package com.medfund.finance.kpi.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * One point on a KPI trend line. The tile UI receives 12 or 24 of these
 * for the sparkline; each element carries the composite scalar for the
 * bucket, the per-currency native breakdown, and any warnings that fired
 * on that bucket's compute (e.g. IBNR pending for that month).
 *
 * <p>Half-open period per plan convention:
 * {@code periodStart} ≤ bucket &lt; {@code periodEnd}.
 */
public record KpiTrendPoint(
        LocalDate periodStart,
        LocalDate periodEnd,
        KpiReportData composite,
        Map<String, KpiValue> perCurrency,
        List<String> warnings) {}
