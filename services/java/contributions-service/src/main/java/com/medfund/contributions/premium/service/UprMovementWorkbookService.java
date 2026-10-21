package com.medfund.contributions.premium.service;

import com.medfund.contributions.premium.dto.UprMovementRow;
import com.medfund.contributions.premium.repository.PremiumReportQueryRepository;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportWorkbook;
import com.medfund.shared.report.ReportingCurrencyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * XLSX renderer for the Phase 12 §B UPR Movement report. One sheet per
 * insurance line + a Summary sheet with cross-currency native totals,
 * best-effort converted grand total to the reporting currency, and a
 * warnings block naming currencies whose FX was unavailable.
 *
 * <p>Mirrors the Phase 11 {@code CommissionWorkbookService} shape:
 * {@link ReportWorkbook} for the actual POI wiring, {@link FxRateReader}
 * for the fail-loud grand-total conversion (missing FX yields a
 * warnings line rather than a partial number).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UprMovementWorkbookService {

    private final PremiumReportQueryRepository queryRepository;
    private final ReportingCurrencyResolver currencyResolver;
    private final FxRateReader fxRateReader;

    public Mono<byte[]> workbook(LocalDate periodStart, LocalDate periodEnd,
                                 String insuranceLine, String overrideCurrency,
                                 UUID tenantId) {
        return currencyResolver.resolve(tenantId, overrideCurrency)
                .flatMap(reportingCurrency -> queryRepository
                        .uprMovementRows(periodStart, periodEnd, insuranceLine)
                        .collectList()
                        .flatMap(rows -> {
                            Map<String, List<UprMovementRow>> byLine =
                                    groupBy(rows, UprMovementRow::insuranceLine);
                            Map<String, BigDecimal> closingByCurrency = closingSumsByCurrency(rows);
                            return convertGrandTotal(closingByCurrency, reportingCurrency, periodEnd, tenantId)
                                    .map(conv -> render(periodStart, periodEnd, insuranceLine,
                                            byLine, closingByCurrency, reportingCurrency, conv.total(), conv.warnings()));
                        }));
    }

    private byte[] render(LocalDate periodStart, LocalDate periodEnd, String insuranceLine,
                          Map<String, List<UprMovementRow>> byLine,
                          Map<String, BigDecimal> closingByCurrency,
                          String reportingCurrency,
                          BigDecimal convertedGrandTotal,
                          List<String> warnings) {
        ReportWorkbook book = ReportWorkbook.newBook();
        String title = "UPR Movement - " + periodStart + " to " + periodEnd;

        List<String> lines = new ArrayList<>(byLine.keySet());
        Collections.sort(lines);
        if (lines.isEmpty()) {
            book.sheet("Empty")
                    .titleMerged(title, 7)
                    .meta("Line",   insuranceLine != null ? insuranceLine : "All")
                    .meta("Period", periodStart + " to " + periodEnd)
                    .meta("Note",   "No earning-schedule rows in this window.")
                    .freezeAtHeader().autoSize();
        }
        for (String line : lines) {
            ReportWorkbook.SheetWriter sheet = book.sheet(line);
            sheet.titleMerged(title + " - " + line, 7)
                    .meta("Line",   line)
                    .meta("Period", periodStart + " to " + periodEnd)
                    .blankRow();
            sheet.header("Currency", "Opening UPR", "Written premium", "Earned premium",
                    "Endorsement delta", "Closing UPR", "Note");
            sheet.forEach(byLine.get(line), (sw, r) -> sw
                    .text(safe(r.currencyCode()))
                    .money(r.openingUpr())
                    .money(r.writtenPremium())
                    .money(r.earnedPremium())
                    .money(r.endorsementDelta())
                    .moneyBold(r.closingUpr())
                    .text(""));
            sheet.freezeAtHeader().autoSize();
        }

        buildSummarySheet(book, title, insuranceLine, periodStart, periodEnd,
                closingByCurrency, reportingCurrency, convertedGrandTotal, warnings);
        return book.toBytes();
    }

    private void buildSummarySheet(ReportWorkbook book, String title,
                                   String insuranceLine,
                                   LocalDate periodStart, LocalDate periodEnd,
                                   Map<String, BigDecimal> closingByCurrency,
                                   String reportingCurrency,
                                   BigDecimal convertedGrandTotal,
                                   List<String> warnings) {
        ReportWorkbook.SheetWriter sheet = book.sheet("Summary");
        sheet.titleMerged(title + " - Summary", 3)
                .meta("Line",   insuranceLine != null ? insuranceLine : "All")
                .meta("Period", periodStart + " to " + periodEnd)
                .meta("As of",  periodEnd.toString())
                .blankRow();

        List<String> currencies = new ArrayList<>(closingByCurrency.keySet());
        Collections.sort(currencies);
        sheet.header("Currency", "Closing UPR (native)", "Note");
        for (String currency : currencies) {
            sheet.text(currency).moneyBold(closingByCurrency.get(currency)).text("Closing UPR");
        }
        sheet.blankRow();
        if (convertedGrandTotal != null) {
            sheet.metaMoney("Converted grand total (" + reportingCurrency + " @ " + periodEnd + ")",
                    convertedGrandTotal);
        } else {
            sheet.meta("Converted grand total (" + reportingCurrency + ")",
                    "FX unavailable for one or more currencies at " + periodEnd
                            + " - converted total omitted");
        }
        if (warnings != null && !warnings.isEmpty()) {
            sheet.blankRow();
            sheet.meta("Warnings", "");
            for (String warning : warnings) sheet.meta("", warning);
        }
        sheet.freezeAtHeader().autoSize();
    }

    // ── FX conversion ───────────────────────────────────────────────────────

    private Mono<GrandTotalWithWarnings> convertGrandTotal(Map<String, BigDecimal> closingByCurrency,
                                                            String reportingCurrency,
                                                            LocalDate asOf, UUID tenantId) {
        if (closingByCurrency.isEmpty()) {
            return Mono.just(new GrandTotalWithWarnings(BigDecimal.ZERO, List.of()));
        }
        List<String> warnings = new ArrayList<>();
        return Flux.fromIterable(closingByCurrency.entrySet())
                .flatMap(e -> fxRateReader.findRate(e.getKey(), reportingCurrency, asOf, tenantId)
                        .map(rate -> e.getValue().multiply(rate))
                        .switchIfEmpty(Mono.defer(() -> {
                            warnings.add("FX not available for " + e.getKey() + "->"
                                    + reportingCurrency + " as of " + asOf);
                            return Mono.empty();
                        })))
                .collectList()
                .map(parts -> parts.size() == closingByCurrency.size()
                        ? new GrandTotalWithWarnings(
                                parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add), warnings)
                        : new GrandTotalWithWarnings(null, warnings));
    }

    private record GrandTotalWithWarnings(BigDecimal total, List<String> warnings) {}

    // ── Plumbing ────────────────────────────────────────────────────────────

    private static Map<String, BigDecimal> closingSumsByCurrency(List<UprMovementRow> rows) {
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (UprMovementRow r : rows) {
            if (r.currencyCode() == null) continue;
            sums.merge(r.currencyCode(),
                    r.closingUpr() != null ? r.closingUpr() : BigDecimal.ZERO,
                    BigDecimal::add);
        }
        return sums;
    }

    private static <T> Map<String, List<T>> groupBy(List<T> rows,
                                                     java.util.function.Function<T, String> keyFn) {
        Map<String, List<T>> grouped = new LinkedHashMap<>();
        for (T row : rows) {
            String key = keyFn.apply(row);
            if (key == null) key = "-";
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static String safe(String s) { return s != null ? s : ""; }
}
