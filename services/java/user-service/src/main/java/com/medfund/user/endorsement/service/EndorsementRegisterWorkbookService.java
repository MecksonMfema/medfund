package com.medfund.user.endorsement.service;

import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportWorkbook;
import com.medfund.shared.report.ReportingCurrencyResolver;
import com.medfund.user.endorsement.dto.EndorsementRegisterRow;
import com.medfund.user.endorsement.repository.EndorsementReportQueryRepository;
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
import java.util.function.Function;

/**
 * XLSX renderer for the Phase 12 §C Phase 9 Endorsement Register —
 * detail sheet with per-endorsement rows ordered by {@code effective_from},
 * plus a Summary sheet with per-currency native totals (magnitude, so
 * lifts don't net against cuts) and a best-effort converted grand total.
 *
 * <p>Uses the shared {@link FxRateReader} (best-effort) rather than a
 * fail-loud FX converter — missing historical FX yields a "FX
 * unavailable" line on the summary sheet per parent-plan G28.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndorsementRegisterWorkbookService {

    private final EndorsementReportQueryRepository queryRepository;
    private final ReportingCurrencyResolver currencyResolver;
    private final FxRateReader fxRateReader;

    public Mono<byte[]> workbook(LocalDate periodStart, LocalDate periodEnd,
                                 String insuranceLine, String status,
                                 String overrideCurrency, UUID tenantId) {
        return currencyResolver.resolve(tenantId, overrideCurrency)
                .flatMap(reportingCurrency -> queryRepository
                        .rows(periodStart, periodEnd, insuranceLine, status)
                        .collectList()
                        .flatMap(rows -> {
                            Map<String, List<EndorsementRegisterRow>> byCurrency =
                                    groupBy(rows, EndorsementRegisterRow::currencyCode);
                            return convertGrandTotalMulti(byCurrency,
                                            r -> r.premiumDelta() == null
                                                    ? BigDecimal.ZERO : r.premiumDelta().abs(),
                                            reportingCurrency, periodEnd, tenantId)
                                    .map(conv -> render(periodStart, periodEnd, insuranceLine, status,
                                            rows, byCurrency, reportingCurrency,
                                            conv.total(), conv.warnings()));
                        }));
    }

    private byte[] render(LocalDate periodStart, LocalDate periodEnd,
                          String insuranceLine, String status,
                          List<EndorsementRegisterRow> rows,
                          Map<String, List<EndorsementRegisterRow>> byCurrency,
                          String reportingCurrency,
                          BigDecimal convertedGrandTotal,
                          List<String> warnings) {
        ReportWorkbook book = ReportWorkbook.newBook();
        String title = "Endorsement Register - " + periodStart + " to " + periodEnd;

        ReportWorkbook.SheetWriter detail = book.sheet("Endorsements");
        detail.titleMerged(title, 14)
                .meta("Line",   insuranceLine != null ? insuranceLine : "All")
                .meta("Status", status != null ? status : "All")
                .meta("Period", periodStart + " to " + periodEnd)
                .meta("Rows",   String.valueOf(rows.size()))
                .blankRow();
        detail.header("Reference", "Policy #", "Source", "Member", "Line", "Change type",
                "Effective from", "Premium delta", "Currency", "Status",
                "Drafted by", "Approved by", "Committed by", "Voided reason");
        detail.forEach(rows, (sw, r) -> sw
                .text(safe(r.reference()))
                .text(shortId(r.policyId()))
                .text(safe(r.policySource()))
                .text(safe(r.memberName()))
                .text(safe(r.insuranceLine()))
                .text(safe(r.changeType()))
                .date(r.effectiveFrom())
                .money(r.premiumDelta())
                .text(safe(r.currencyCode()))
                .text(safe(r.status()))
                .text(safe(r.draftActorEmail()))
                .text(safe(r.approveActorEmail()))
                .text(safe(r.commitActorEmail()))
                .text(safe(r.voidedReason())));
        detail.freezeAtHeader().autoSize();

        buildSummarySheet(book, title, insuranceLine, status, periodStart, periodEnd,
                byCurrency, reportingCurrency, convertedGrandTotal, warnings);
        return book.toBytes();
    }

    private void buildSummarySheet(ReportWorkbook book, String title,
                                   String insuranceLine, String status,
                                   LocalDate periodStart, LocalDate periodEnd,
                                   Map<String, List<EndorsementRegisterRow>> byCurrency,
                                   String reportingCurrency,
                                   BigDecimal convertedGrandTotal, List<String> warnings) {
        ReportWorkbook.SheetWriter sheet = book.sheet("Summary");
        sheet.titleMerged(title + " - Summary", 3)
                .meta("Line",   insuranceLine != null ? insuranceLine : "All")
                .meta("Status", status != null ? status : "All")
                .meta("Period", periodStart + " to " + periodEnd)
                .meta("As of",  periodEnd.toString())
                .blankRow();

        List<String> currencies = new ArrayList<>(byCurrency.keySet());
        Collections.sort(currencies);
        sheet.header("Currency", "|Delta| (native)", "Note");
        for (String currency : currencies) {
            BigDecimal total = sumBigDecimal(byCurrency.get(currency),
                    r -> r.premiumDelta() == null ? BigDecimal.ZERO : r.premiumDelta().abs());
            sheet.text(currency).moneyBold(total).text("Sum of |premium_delta|");
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
            for (String w : warnings) sheet.meta("", w);
        }
        sheet.freezeAtHeader().autoSize();
    }

    // ── FX conversion (mirrors NewBusinessRegisterWorkbookService) ─────────

    private <T> Mono<GrandTotalWithWarnings> convertGrandTotalMulti(
            Map<String, List<T>> byCurrency, Function<T, BigDecimal> amountFn,
            String reportingCurrency, LocalDate asOf, UUID tenantId) {
        if (byCurrency.isEmpty()) return Mono.just(new GrandTotalWithWarnings(BigDecimal.ZERO, List.of()));
        List<String> warnings = new ArrayList<>();
        return Flux.fromIterable(byCurrency.entrySet())
                .flatMap(e -> {
                    BigDecimal nativeSum = sumBigDecimal(e.getValue(), amountFn);
                    return fxRateReader.findRate(e.getKey(), reportingCurrency, asOf, tenantId)
                            .map(nativeSum::multiply)
                            .switchIfEmpty(Mono.defer(() -> {
                                warnings.add("FX not available for " + e.getKey() + "->"
                                        + reportingCurrency + " as of " + asOf);
                                return Mono.empty();
                            }));
                })
                .collectList()
                .map(parts -> parts.size() == byCurrency.size()
                        ? new GrandTotalWithWarnings(
                                parts.stream().reduce(BigDecimal.ZERO, BigDecimal::add), warnings)
                        : new GrandTotalWithWarnings(null, warnings));
    }

    private record GrandTotalWithWarnings(BigDecimal total, List<String> warnings) {}

    // ── Plumbing ────────────────────────────────────────────────────────────

    private static <T> Map<String, List<T>> groupBy(List<T> rows, Function<T, String> keyFn) {
        Map<String, List<T>> grouped = new LinkedHashMap<>();
        for (T row : rows) {
            String key = keyFn.apply(row);
            if (key == null) key = "-";
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    private static <T> BigDecimal sumBigDecimal(List<T> rows, Function<T, BigDecimal> amountFn) {
        BigDecimal sum = BigDecimal.ZERO;
        for (T row : rows) {
            BigDecimal v = amountFn.apply(row);
            if (v != null) sum = sum.add(v);
        }
        return sum;
    }

    private static String shortId(UUID id) {
        if (id == null) return "";
        String s = id.toString();
        return s.length() > 8 ? s.substring(0, 8) : s;
    }

    private static String safe(String s) { return s != null ? s : ""; }
}
