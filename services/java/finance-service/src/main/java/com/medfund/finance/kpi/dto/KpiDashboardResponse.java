package com.medfund.finance.kpi.dto;

import com.medfund.shared.report.ReportResponse;

import java.util.Map;

/**
 * Batch endpoint response — envelopes keyed by {@link com.medfund.shared.report.ReportKey#name()}.
 * A key whose {@code @RequiresReport} gate fires 403 individually surfaces as
 * a whole-payload 403 (K17): the batch endpoint is a convenience for the
 * dashboard's first paint, not an escape hatch around the per-KPI gate.
 */
public record KpiDashboardResponse(
        Map<String, ReportResponse<KpiReportData>> tiles) {}
