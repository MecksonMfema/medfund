package com.medfund.finance.report.schedule.adapter;

import com.medfund.finance.report.schedule.ScheduledFireContext;
import com.medfund.finance.report.schedule.ScheduledReportShapeAdapter;
import com.medfund.finance.service.CollectionRateExcelService;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriodShape;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CollectionRateAdapter implements ScheduledReportShapeAdapter {

    private final CollectionRateExcelService workbookService;

    @Override public ReportKey key() { return ReportKey.COLLECTION_RATE; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        log.debug("[scheduled-adapter] COLLECTION_RATE tenant={} period={}..{}",
                ctx.tenantId(), ctx.periodStart(), ctx.periodEnd());
        List<String> warnings = new ArrayList<>();
        return workbookService.workbook(ctx.periodStart(), ctx.periodEnd(), warnings);
    }
}
