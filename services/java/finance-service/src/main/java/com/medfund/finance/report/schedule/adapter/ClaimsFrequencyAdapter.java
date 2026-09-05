package com.medfund.finance.report.schedule.adapter;

import com.medfund.finance.kpi.dto.KpiRequest;
import com.medfund.finance.kpi.service.KpiWorkbookService;
import com.medfund.finance.report.schedule.ScheduledFireContext;
import com.medfund.finance.report.schedule.ScheduledReportShapeAdapter;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriodShape;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Phase 18 §Phase 8 — scheduled-email adapter for the CLAIMS_FREQUENCY KPI.
 * Count of claims per policy-month-in-force; dimensionless (a single
 * reporting-currency-labelled entry keeps the tile shape uniform).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClaimsFrequencyAdapter implements ScheduledReportShapeAdapter {

    private final KpiWorkbookService workbookService;

    @Override public ReportKey key() { return ReportKey.CLAIMS_FREQUENCY; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        log.debug("[scheduled-adapter] CLAIMS_FREQUENCY tenant={} period={}..{}",
                ctx.tenantId(), ctx.periodStart(), ctx.periodEnd());
        return workbookService.workbook(ReportKey.CLAIMS_FREQUENCY,
                new KpiRequest(ctx.tenantId(), ctx.periodStart(), ctx.periodEnd(),
                        ctx.reportingCurrency(), null, null, null));
    }
}
