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
 * Phase 18 §Phase 8 — scheduled-email adapter for the LOSS_RATIO_KPI. K13
 * filter chips (insuranceLine, schemeId, producerId) are not exposed on the
 * schedule form; the fire always renders the tenant-wide slice for the last
 * complete period.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LossRatioKpiAdapter implements ScheduledReportShapeAdapter {

    private final KpiWorkbookService workbookService;

    @Override public ReportKey key() { return ReportKey.LOSS_RATIO_KPI; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        log.debug("[scheduled-adapter] LOSS_RATIO_KPI tenant={} period={}..{}",
                ctx.tenantId(), ctx.periodStart(), ctx.periodEnd());
        return workbookService.workbook(ReportKey.LOSS_RATIO_KPI,
                new KpiRequest(ctx.tenantId(), ctx.periodStart(), ctx.periodEnd(),
                        ctx.reportingCurrency(), null, null, null));
    }
}
