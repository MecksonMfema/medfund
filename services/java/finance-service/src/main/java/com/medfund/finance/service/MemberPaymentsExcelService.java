package com.medfund.finance.service;

import com.medfund.finance.dto.MemberPaymentsReportResponse;
import com.medfund.finance.dto.MemberPaymentsReportResponse.MemberPaymentRow;
import com.medfund.shared.report.ReportWorkbook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * XLSX generator for the member-payments unified report. One row per
 * (member, currency) with the billed / received / claims-paid legs and
 * the derived net position. An empty rows list produces a header-only
 * sheet so the file shape stays stable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberPaymentsExcelService {

    private final CrossServiceReportService reportService;

    public Mono<byte[]> workbook(LocalDate periodStart, LocalDate periodEnd,
                                 List<String> warnings) {
        return reportService.memberPayments(periodStart, periodEnd, warnings)
                .map(report -> render(report, warnings));
    }

    private byte[] render(MemberPaymentsReportResponse report, List<String> warnings) {
        ReportWorkbook book = ReportWorkbook.newBook();
        ReportWorkbook.SheetWriter sheet = book.sheet("Member payments")
                .titleMerged("Member payments — unified", 6)
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

        sheet.header("Member", "Currency",
                "Total billed", "Total received", "Total claims paid", "Net position");

        if (report.rows() != null) {
            sheet.forEach(report.rows(), (sw, row) ->
                    sw.text(row.memberName())
                            .text(row.currencyCode())
                            .moneyBold(row.totalBilled())
                            .moneyBold(row.totalReceived())
                            .moneyBold(row.totalClaimsPaid())
                            .money(row.netPosition()));
        }

        sheet.freezeAtHeader().autoSize();
        return book.toBytes();
    }
}
