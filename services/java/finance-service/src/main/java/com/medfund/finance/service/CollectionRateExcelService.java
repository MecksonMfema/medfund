package com.medfund.finance.service;

import com.medfund.finance.dto.CollectionRateReportResponse;
import com.medfund.finance.dto.CollectionRateReportResponse.DimensionRow;
import com.medfund.finance.dto.CollectionRateReportResponse.MonthlyBucket;
import com.medfund.shared.report.ReportWorkbook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * XLSX generator for the collection-rate report. One sheet per dimension
 * (Scheme / Group / Member); each sheet has one row per (dimension,
 * currency) with expanded monthly columns showing billed / received /
 * rate for each month in the reporting window. Empty dimension lists
 * produce a sheet with the header row only — treasurer can eyeball
 * "no data" without a mysterious missing sheet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CollectionRateExcelService {

    private final CollectionRateReportService reportService;

    public Mono<byte[]> workbook(LocalDate periodStart, LocalDate periodEnd,
                                  List<String> warnings) {
        return reportService.compute(periodStart, periodEnd, warnings)
                .map(report -> render(report, warnings));
    }

    private byte[] render(CollectionRateReportResponse report, List<String> warnings) {
        ReportWorkbook book = ReportWorkbook.newBook();
        renderDimensionSheet(book, "Per Scheme", report.byScheme(), warnings, report);
        renderDimensionSheet(book, "Per Group", report.byGroup(), warnings, report);
        // Members sheet is always present so the file shape doesn't shift
        // between tenants that have no member-level receipts and those
        // that do.
        renderDimensionSheet(book, "Per Member", report.byMember(), warnings, report);
        return book.toBytes();
    }

    private void renderDimensionSheet(ReportWorkbook book, String sheetName,
                                       List<DimensionRow> rows, List<String> warnings,
                                       CollectionRateReportResponse report) {
        ReportWorkbook.SheetWriter sheet = book.sheet(sheetName)
                .titleMerged("Collection rate — " + sheetName.toLowerCase(), 8)
                .meta("Period start", report.periodStart() != null ? report.periodStart().toString() : "—")
                .meta("Period end",   report.periodEnd()   != null ? report.periodEnd().toString()   : "—")
                .meta("Rows", String.valueOf(rows != null ? rows.size() : 0));

        if (warnings != null && !warnings.isEmpty()) {
            sheet.meta("Warnings", String.valueOf(warnings.size()));
            for (String w : warnings) {
                sheet.meta("", w);
            }
        }
        sheet.blankRow();

        sheet.header("Dimension", "Currency",
                "Total billed", "Total received", "Rate %",
                "Months", "First month", "Last month");

        if (rows != null) {
            sheet.forEach(rows, (sw, row) -> {
                List<MonthlyBucket> months = row.monthlyBuckets() != null ? row.monthlyBuckets() : List.of();
                LocalDate first = months.isEmpty() ? null : months.get(0).month();
                LocalDate last  = months.isEmpty() ? null : months.get(months.size() - 1).month();
                sw.text(row.dimensionName())
                        .text(row.currencyCode())
                        .moneyBold(row.totalBilled())
                        .moneyBold(row.totalReceived())
                        .money(row.totalRatePct())
                        .number((long) months.size())
                        .date(first)
                        .date(last);
            });
        }

        sheet.freezeAtHeader().autoSize();
    }
}
