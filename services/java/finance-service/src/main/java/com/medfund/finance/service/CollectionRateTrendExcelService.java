package com.medfund.finance.service;

import com.medfund.finance.dto.CollectionRateTrendResponse;
import com.medfund.finance.dto.CollectionRateTrendResponse.MonthRow;
import com.medfund.shared.report.ReportWorkbook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * XLSX generator for the portfolio-level collection-rate trend. One
 * Trend sheet, one row per (month, currency) — the same flat strip the
 * API returns, so the workbook is trivially traceable to the report.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionRateTrendExcelService {

    private final CollectionRateTrendService trendService;

    public Mono<byte[]> workbook(LocalDate periodStart, LocalDate periodEnd,
                                  List<String> warnings) {
        return trendService.compute(periodStart, periodEnd, warnings)
                .map(report -> render(report, warnings));
    }

    private byte[] render(CollectionRateTrendResponse report, List<String> warnings) {
        ReportWorkbook book = ReportWorkbook.newBook();
        ReportWorkbook.SheetWriter sheet = book.sheet("Trend")
                .titleMerged("Collection-rate trend", 5)
                .meta("Period start", report.periodStart() != null ? report.periodStart().toString() : "—")
                .meta("Period end",   report.periodEnd()   != null ? report.periodEnd().toString()   : "—")
                .meta("Rows", String.valueOf(report.months().size()));

        if (warnings != null && !warnings.isEmpty()) {
            sheet.meta("Warnings", String.valueOf(warnings.size()));
            for (String w : warnings) {
                sheet.meta("", w);
            }
        }
        sheet.blankRow();

        sheet.header("Month", "Currency", "Billed", "Received", "Rate %");

        sheet.forEach(report.months(), (sw, row) -> sw.date(row.month())
                .text(row.currencyCode())
                .money(row.billed())
                .money(row.received())
                .money(row.ratePct()));

        sheet.freezeAtHeader().autoSize();
        return book.toBytes();
    }
}
