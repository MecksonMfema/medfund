package com.medfund.finance.report.schedule.adapter;

import com.medfund.finance.reinsurance.service.BordereauReportWorkbookService;
import com.medfund.finance.report.schedule.ScheduledFireContext;
import com.medfund.finance.report.schedule.ScheduledReportShapeAdapter;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriodShape;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReinsuranceRecoveriesAdapter implements ScheduledReportShapeAdapter {

    private final BordereauReportWorkbookService workbookService;

    @Override public ReportKey key() { return ReportKey.REINSURANCE_RECOVERIES; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        int year = ctx.periodStart().getYear();
        int quarter = ReinsuranceCessionBordereauAdapter.quarterOf(ctx.periodStart());
        log.debug("[scheduled-adapter] REINSURANCE_RECOVERIES tenant={} Q{} {}",
                ctx.tenantId(), quarter, year);
        return workbookService.recoveriesWorkbook(
                null,
                null,
                year,
                quarter,
                ctx.reportingCurrency(),
                ctx.tenantId());
    }
}
