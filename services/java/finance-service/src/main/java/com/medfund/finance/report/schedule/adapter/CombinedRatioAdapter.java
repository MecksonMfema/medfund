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
 * Phase 18 §Phase 8 — scheduled-email adapter for the COMBINED_RATIO KPI.
 * Sum of LOSS_RATIO_KPI + EXPENSE_RATIO on mixed basis
 * ({@code MIXED_LOSS_EARNED_EXPENSE_WRITTEN} — NAIC convention).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CombinedRatioAdapter implements ScheduledReportShapeAdapter {

    private final KpiWorkbookService workbookService;

    @Override public ReportKey key() { return ReportKey.COMBINED_RATIO; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        log.debug("[scheduled-adapter] COMBINED_RATIO tenant={} period={}..{}",
                ctx.tenantId(), ctx.periodStart(), ctx.periodEnd());
        return workbookService.workbook(ReportKey.COMBINED_RATIO,
                new KpiRequest(ctx.tenantId(), ctx.periodStart(), ctx.periodEnd(),
                        ctx.reportingCurrency(), null, null, null));
    }
}
