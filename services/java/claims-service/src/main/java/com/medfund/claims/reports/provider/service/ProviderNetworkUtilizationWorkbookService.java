package com.medfund.claims.reports.provider.service;

import com.medfund.claims.reports.provider.dto.NetworkTierTotals;
import com.medfund.claims.reports.provider.dto.ProviderUtilizationResult;
import com.medfund.claims.reports.provider.dto.ProviderUtilizationRow;
import com.medfund.shared.report.ReportWorkbook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Phase 13 §C Phase 9 per L12 — two-sheet workbook: per-tier summary
 * + per-provider detail. Envelope warnings (peer-down, missing FX) are
 * copied onto the summary sheet.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderNetworkUtilizationWorkbookService {

    private final ProviderNetworkUtilizationReportService reportService;

    public Mono<byte[]> workbook(LocalDate periodStart, LocalDate periodEnd,
                                 String insuranceLine, String networkTier,
                                 String overrideCurrency) {
        return reportService.generate(periodStart, periodEnd, insuranceLine, networkTier, overrideCurrency)
                .map(response -> render(periodStart, periodEnd, insuranceLine, networkTier,
                        response.data(), response.warnings()));
    }

    private byte[] render(LocalDate periodStart, LocalDate periodEnd,
                          String insuranceLine, String networkTier,
                          ProviderUtilizationResult result, List<String> warnings) {
        ReportWorkbook book = ReportWorkbook.newBook();
        String title = "Provider Network Utilization — " + periodStart + " to " + periodEnd;

        ReportWorkbook.SheetWriter summary = book.sheet("Summary");
        summary.titleMerged(title + " — Summary", 7)
                .meta("Line",   insuranceLine != null ? insuranceLine : "All")
                .meta("Tier",   networkTier != null ? networkTier : "All")
                .meta("Period", periodStart + " to " + periodEnd)
                .blankRow();
        summary.header("Network tier", "Providers", "Claims", "Denials",
                "Total claimed", "Total paid", "Unique members");
        for (Map.Entry<String, NetworkTierTotals> e : result.summary().entrySet()) {
            NetworkTierTotals t = e.getValue();
            summary.text(e.getKey())
                    .number(t.providerCount())
                    .number(t.claimCount())
                    .number(t.denialCount())
                    .money(t.totalClaimed())
                    .money(t.totalPaid())
                    .number(t.uniqueMembers());
        }
        if (warnings != null && !warnings.isEmpty()) {
            summary.blankRow().meta("Warnings", "");
            for (String w : warnings) summary.meta("", w);
        }
        summary.freezeAtHeader().autoSize();

        ReportWorkbook.SheetWriter detail = book.sheet("Providers");
        detail.titleMerged(title + " — Per-provider detail", 8)
                .meta("Rows", String.valueOf(result.detail().size()))
                .blankRow();
        detail.header("Provider", "Network tier", "Line", "Currency",
                "Claims", "Denials", "Total claimed", "Total paid", "Unique members");
        for (ProviderUtilizationRow r : result.detail()) {
            detail.text(nz(r.providerName()))
                    .text(nz(r.networkTier()))
                    .text(nz(r.insuranceLine()))
                    .text(nz(r.currencyCode()))
                    .number(r.claimCount())
                    .number(r.denialCount())
                    .money(r.totalClaimed())
                    .money(r.totalPaid())
                    .number(r.uniqueMembers());
        }
        detail.freezeAtHeader().autoSize();

        return book.toBytes();
    }

    private static String nz(String s) { return s != null ? s : ""; }
}
