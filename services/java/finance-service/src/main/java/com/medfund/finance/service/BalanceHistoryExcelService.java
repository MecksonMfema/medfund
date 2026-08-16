package com.medfund.finance.service;

import com.medfund.finance.dto.BalanceHistoryResponse;
import com.medfund.shared.report.ReportWorkbook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * XLSX generator for the balance-history report (Phase 6). One row per
 * (run, currency) with the frozen ledger — opening = closing = the live
 * outstanding balance at run execution, plus the run's net due (D6-1).
 * An empty rows list produces a header-only sheet so the file shape stays
 * stable.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BalanceHistoryExcelService {

    private final BalanceHistoryService historyService;

    public Mono<byte[]> providerWorkbook(UUID providerId, UUID asAtRun, String currency) {
        return historyService.providerHistory(providerId, asAtRun, currency)
                .map(report -> render(report, "Balance history"));
    }

    public Mono<byte[]> memberWorkbook(UUID memberId, UUID asAtRun, String currency) {
        return historyService.memberHistory(memberId, asAtRun, currency)
                .map(report -> render(report, "Balance history"));
    }

    private byte[] render(BalanceHistoryResponse report, String title) {
        ReportWorkbook book = ReportWorkbook.newBook();
        ReportWorkbook.SheetWriter sheet = book.sheet(title)
                .titleMerged(title, 8)
                .meta("Payee", report.payeeName() != null && !report.payeeName().isBlank()
                        ? report.payeeName() : "—")
                .meta("Rows", String.valueOf(report.rows() != null ? report.rows().size() : 0));
        sheet.blankRow();

        sheet.header("Run number", "Executed at", "Currency",
                "Opening balance", "Closing balance",
                "Total claimed", "Total approved", "Total paid", "Net due");

        if (report.rows() != null) {
            sheet.forEach(report.rows(), (sw, row) ->
                    sw.text(row.runNumber() != null ? row.runNumber() : "—")
                            .text(row.executedAt() != null ? row.executedAt().toString() : "—")
                            .text(row.currencyCode())
                            .moneyBold(row.openingBalance())
                            .moneyBold(row.closingBalance())
                            .money(row.totalClaimed())
                            .money(row.totalApproved())
                            .money(row.totalPaid())
                            .moneyBold(row.netDue()));
        }

        sheet.freezeAtHeader().autoSize();
        return book.toBytes();
    }
}
