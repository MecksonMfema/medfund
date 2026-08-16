package com.medfund.finance.service;

import com.medfund.finance.dto.LossRatioReportResponse;
import com.medfund.finance.dto.LossRatioReportResponse.LossRatioRow;
import com.medfund.shared.report.ReportWorkbook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * XLSX generator for the loss-ratio (billing vs claims) report. One row
 * per (scheme, currency) with the full claims funnel and the derived
 * paid/billed ratio + billed-minus-paid shortfall. An empty rows list
 * produces a header-only sheet so the file shape stays stable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LossRatioExcelService {

    private final CrossServiceReportService reportService;

    public Mono<byte[]> workbook(LocalDate periodStart, LocalDate periodEnd,
                                 List<String> warnings) {
        return reportService.lossRatio(periodStart, periodEnd, warnings)
                .map(report -> render(report, warnings));
    }

    private byte[] render(LossRatioReportResponse report, List<String> warnings) {
        ReportWorkbook book = ReportWorkbook.newBook();
        ReportWorkbook.SheetWriter sheet = book.sheet("Loss ratio")
                .titleMerged("Loss ratio (billing vs claims)", 8)
                .meta("Period start", report.periodStart() != null ? report.periodStart().toString() : "—")
                .meta("Period end",   report.periodEnd()   != null ? report.periodEnd().toString()   : "—")
                .meta("Rows", String.valueOf(report.rows() != null ? report.rows().size() : 0));

        if (warnings != null && !warnings.isEmpty()) {
            sheet.meta("Warnings", String.valueOf(warnings.size()));
            for (String w : warnings) {
                sheet.meta("", w);
            }
        }
        sheet.blankRow();

        sheet.header("Scheme", "Currency",
                "Total billed", "Total claimed", "Total approved", "Total paid",
                "Paid ratio %", "Billed − paid");

        if (report.rows() != null) {
            sheet.forEach(report.rows(), (sw, row) ->
                    sw.text(row.schemeName())
                            .text(row.currencyCode())
                            .moneyBold(row.totalBilled())
                            .moneyBold(row.totalClaimed())
                            .moneyBold(row.totalApproved())
                            .moneyBold(row.totalPaid())
                            .money(row.paidRatioPct())
                            .money(row.billedMinusPaid()));
        }

        sheet.freezeAtHeader().autoSize();
        return book.toBytes();
    }
}
