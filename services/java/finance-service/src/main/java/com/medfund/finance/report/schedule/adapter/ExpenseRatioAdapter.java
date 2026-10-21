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
 * Phase 18 §Phase 8 — scheduled-email adapter for the EXPENSE_RATIO KPI (UI
 * label: Acquisition ratio). K7: v1 sums all PAID commission without
 * acquisition/servicing split.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExpenseRatioAdapter implements ScheduledReportShapeAdapter {

    private final KpiWorkbookService workbookService;

    @Override public ReportKey key() { return ReportKey.EXPENSE_RATIO; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        log.debug("[scheduled-adapter] EXPENSE_RATIO tenant={} period={}..{}",
                ctx.tenantId(), ctx.periodStart(), ctx.periodEnd());
        return workbookService.workbook(ReportKey.EXPENSE_RATIO,
                new KpiRequest(ctx.tenantId(), ctx.periodStart(), ctx.periodEnd(),
                        ctx.reportingCurrency(), null, null, null));
    }
}
