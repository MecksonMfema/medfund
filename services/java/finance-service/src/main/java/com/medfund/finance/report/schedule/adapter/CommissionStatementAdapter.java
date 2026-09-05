package com.medfund.finance.report.schedule.adapter;

import com.medfund.finance.producer.service.CommissionWorkbookService;
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
public class CommissionStatementAdapter implements ScheduledReportShapeAdapter {

    private final CommissionWorkbookService workbookService;

    @Override public ReportKey key() { return ReportKey.COMMISSION_STATEMENT; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        log.debug("[scheduled-adapter] COMMISSION_STATEMENT tenant={} period={}..{}",
                ctx.tenantId(), ctx.periodStart(), ctx.periodEnd());
        return workbookService.statementWorkbook(
                ctx.periodStart(),
                ctx.periodEnd(),
                null,
                ctx.reportingCurrency(),
                ctx.tenantId());
    }
}
