package com.medfund.user.reports.lifecycle.service;

import com.medfund.shared.report.ReportWorkbook;
import com.medfund.user.reports.lifecycle.dto.PolicyMovementResult;
import com.medfund.user.reports.lifecycle.dto.PolicyMovementRow;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 13 §C Phase 8 workbook renderer for POLICY_MOVEMENT. One row per
 * (policy_source, currency) pair on the detail sheet, plus a
 * per-currency summary sheet with |written_added + written_removed|
 * magnitude native totals.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyMovementWorkbookService {

    private final PolicyMovementReportService reportService;

    public Mono<byte[]> workbook(LocalDate periodStart, LocalDate periodEnd, String overrideCurrency) {
        return reportService.generate(periodStart, periodEnd, overrideCurrency)
                .map(response -> render(periodStart, periodEnd, response.data().rows(),
                        response.reportingCurrency()));
    }

    private byte[] render(LocalDate periodStart, LocalDate periodEnd,
                          List<PolicyMovementRow> rows, String reportingCurrency) {
        ReportWorkbook book = ReportWorkbook.newBook();
        String title = "Policy Movement - " + periodStart + " to " + periodEnd;

        ReportWorkbook.SheetWriter detail = book.sheet("Movement");
        detail.titleMerged(title, 14)
                .meta("Period", periodStart + " to " + periodEnd)
                .meta("Rows",   String.valueOf(rows.size()))
                .blankRow();
        detail.header("Policy source", "Line", "Currency",
                "Opening", "New business", "Renewed",
                "Lapsed", "Terminated", "Closing",
                "Written premium added", "Written premium removed");
        detail.forEach(rows, (sw, r) -> sw
                .text(nz(r.policySource()))
                .text(nz(r.insuranceLine()))
                .text(nz(r.currencyCode()))
                .number(r.openingCount())
                .number(r.newBusinessCount())
                .number(r.renewedCount())
                .number(r.lapsedCount())
                .number(r.terminatedCount())
                .number(r.closingCount())
                .money(r.writtenPremiumAdded())
                .money(r.writtenPremiumRemoved()));
        detail.freezeAtHeader().autoSize();

        ReportWorkbook.SheetWriter summary = book.sheet("Summary");
        summary.titleMerged(title + " - Summary", 4)
                .meta("Reporting currency", reportingCurrency != null ? reportingCurrency : "-")
                .meta("Period", periodStart + " to " + periodEnd)
                .blankRow();
        Map<String, BigDecimal[]> byCurrency = new LinkedHashMap<>();
        for (PolicyMovementRow r : rows) {
            BigDecimal[] agg = byCurrency.computeIfAbsent(nz(r.currencyCode()),
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            if (r.writtenPremiumAdded() != null)   agg[0] = agg[0].add(r.writtenPremiumAdded());
            if (r.writtenPremiumRemoved() != null) agg[1] = agg[1].add(r.writtenPremiumRemoved());
        }
        summary.header("Currency", "Added (native)", "Removed (native)", "|Net| (native)");
        for (Map.Entry<String, BigDecimal[]> e : byCurrency.entrySet()) {
            BigDecimal net = e.getValue()[0].subtract(e.getValue()[1]).abs();
            summary.text(e.getKey())
                    .money(e.getValue()[0])
                    .money(e.getValue()[1])
                    .moneyBold(net);
        }
        summary.freezeAtHeader().autoSize();

        return book.toBytes();
    }

    private static String nz(String s) { return s != null ? s : ""; }
}
