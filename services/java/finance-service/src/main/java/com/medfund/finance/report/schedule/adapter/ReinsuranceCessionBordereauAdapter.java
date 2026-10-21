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

import java.time.LocalDate;

/**
 * The underlying {@link BordereauReportWorkbookService#cessionWorkbook(java.util.UUID,
 * java.util.UUID, int, int, String, java.util.UUID)} signature is
 * quarter-based; the adapter derives year+quarter from the fire's
 * {@code periodStart}. If admin schedules MONTHLY cadence for this report,
 * the adapter picks the quarter that contains the previous complete month —
 * a graceful degradation rather than a hard error.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReinsuranceCessionBordereauAdapter implements ScheduledReportShapeAdapter {

    private final BordereauReportWorkbookService workbookService;

    @Override public ReportKey key() { return ReportKey.REINSURANCE_CESSION_BORDEREAU; }
    @Override public ReportPeriodShape periodShape() { return ReportPeriodShape.PREVIOUS_COMPLETE_PERIOD; }

    @Override
    public Mono<byte[]> render(ScheduledFireContext ctx) {
        int year = ctx.periodStart().getYear();
        int quarter = quarterOf(ctx.periodStart());
        log.debug("[scheduled-adapter] REINSURANCE_CESSION_BORDEREAU tenant={} Q{} {}",
                ctx.tenantId(), quarter, year);
        return workbookService.cessionWorkbook(
                null,
                null,
                year,
                quarter,
                ctx.reportingCurrency(),
                ctx.tenantId());
    }

    static int quarterOf(LocalDate date) {
        return ((date.getMonthValue() - 1) / 3) + 1;
    }
}
