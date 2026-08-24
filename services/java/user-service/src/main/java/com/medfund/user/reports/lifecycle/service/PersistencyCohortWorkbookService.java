package com.medfund.user.reports.lifecycle.service;

import com.medfund.shared.report.ReportWorkbook;
import com.medfund.user.reports.lifecycle.dto.PersistencyCohortResult;
import com.medfund.user.reports.lifecycle.dto.PersistencyCohortRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * Phase 13 §C Phase 8 workbook renderer for PERSISTENCY_COHORT — one
 * detail sheet with a row per (cohort_month, line, checkpoint), plus a
 * freshness warning stamped into the header metadata when the matview
 * is stale.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PersistencyCohortWorkbookService {

    private final PersistencyCohortReportService reportService;

    public Mono<byte[]> workbook(LocalDate periodStart, LocalDate periodEnd,
                                 List<Integer> checkpoints, String insuranceLine,
                                 String overrideCurrency) {
        return reportService.generate(periodStart, periodEnd, checkpoints, insuranceLine, overrideCurrency)
                .map(response -> render(periodStart, periodEnd, insuranceLine,
                        response.data()));
    }

    private byte[] render(LocalDate periodStart, LocalDate periodEnd, String insuranceLine,
                          PersistencyCohortResult result) {
        ReportWorkbook book = ReportWorkbook.newBook();
        String title = "Persistency Cohort — " + periodStart + " to " + periodEnd;

        ReportWorkbook.SheetWriter detail = book.sheet("Persistency");
        detail.titleMerged(title, 6)
                .meta("Line",   insuranceLine != null ? insuranceLine : "All")
                .meta("Period", periodStart + " to " + periodEnd)
                .meta("Rows",   String.valueOf(result.rows().size()));
        if (result.freshnessWarning() != null && !result.freshnessWarning().isBlank()) {
            detail.meta("Freshness", result.freshnessWarning());
        }
        detail.blankRow();
        detail.header("Cohort month", "Line", "Checkpoint (months)",
                "Cohort size", "Still active", "Retention %");
        detail.forEach(result.rows(), (sw, r) -> sw
                .date(r.cohortMonth())
                .text(nz(r.insuranceLine()))
                .number((long) r.checkpointMonths())
                .number(r.cohortSize())
                .number(r.stillActive())
                .money(r.retentionRate()));
        detail.freezeAtHeader().autoSize();

        return book.toBytes();
    }

    private static String nz(String s) { return s != null ? s : ""; }
}
