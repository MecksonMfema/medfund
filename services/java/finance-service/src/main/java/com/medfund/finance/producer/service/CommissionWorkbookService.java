package com.medfund.finance.producer.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.producer.dto.ClawbackRegisterRow;
import com.medfund.finance.producer.dto.CommissionStatementRow;
import com.medfund.finance.producer.repository.CommissionReportQueryRepository;
import com.medfund.finance.producer.repository.ProducerRepository;
import com.medfund.shared.report.ReportWorkbook;
import com.medfund.shared.report.ReportingCurrencyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Multi-sheet XLSX renderer for the two Phase 11 §A Phase 4 commission
 * reports. Same shape as {@code BordereauReportWorkbookService}: one sheet
 * per distinct grouping (currency for the statement, source for the
 * clawback register), plus a Summary sheet carrying per-currency native
 * subtotals + a best-effort converted grand total in the tenant reporting
 * currency (falling back to a warning line when FX is unavailable per
 * parent-plan G28).
 *
 * <p>Rows stay native — the grand-total conversion at the Summary sheet
 * uses per-currency FX from {@link FxConverter}; if ANY currency's rate is
 * missing, the Summary omits the converted total and inserts a warning
 * line, matching the reinsurance precedent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CommissionWorkbookService {

    private final CommissionReportQueryRepository queryRepository;
    private final ProducerRepository producerRepository;
    private final ReportingCurrencyResolver currencyResolver;
    private final FxConverter fxConverter;

    // ── Commission statement ────────────────────────────────────────────────

    public Mono<byte[]> statementWorkbook(LocalDate periodStart, LocalDate periodEnd,
                                          UUID producerId, String overrideCurrency,
                                          UUID tenantId) {
        OffsetDateTime from = periodStart.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to   = periodEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Mono<String> producerName = producerId != null
                ? producerRepository.findById(producerId)
                        .map(p -> p.getName() + " (" + p.getProducerCode() + ")")
                        .defaultIfEmpty("Producer " + producerId)
                : Mono.just("All producers");

        return currencyResolver.resolve(tenantId, overrideCurrency)
                .flatMap(reportingCurrency -> Mono.zip(
                        producerName,
                        queryRepository.statementRows(from, to, producerId).collectList()
                ).flatMap(tuple -> {
                    String pName = tuple.getT1();
                    List<CommissionStatementRow> rows = tuple.getT2();

                    Map<String, List<CommissionStatementRow>> byCurrency =
                            groupBy(rows, CommissionStatementRow::nativeCurrency);
                    return convertGrandTotalMulti(byCurrency,
                                    CommissionStatementRow::nativeAmount,
                                    reportingCurrency, periodEnd, tenantId)
                            .map(converted -> renderStatementBook(periodStart, periodEnd,
                                    pName, byCurrency, reportingCurrency, converted))
                            .defaultIfEmpty(renderStatementBook(periodStart, periodEnd,
                                    pName, byCurrency, reportingCurrency, null));
                }));
    }

    private byte[] renderStatementBook(LocalDate periodStart, LocalDate periodEnd,
                                       String producerLabel,
                                       Map<String, List<CommissionStatementRow>> byCurrency,
                                       String reportingCurrency,
                                       BigDecimal convertedGrandTotal) {
        ReportWorkbook book = ReportWorkbook.newBook();
        String title = "Commission Statement — " + periodStart + " to " + periodEnd;

        List<String> currencies = new ArrayList<>(byCurrency.keySet());
        Collections.sort(currencies);
        if (currencies.isEmpty()) {
            book.sheet("Empty")
                    .titleMerged(title, 13)
                    .meta("Producer", producerLabel)
                    .meta("Period",   periodStart + " to " + periodEnd)
                    .meta("Note",     "No commission accruals in this period.")
                    .freezeAtHeader().autoSize();
        }
        for (String currency : currencies) {
            List<CommissionStatementRow> sheetRows = byCurrency.get(currency);
            BigDecimal sheetTotal = sumBigDecimal(sheetRows, CommissionStatementRow::nativeAmount);

            ReportWorkbook.SheetWriter sheet = book.sheet("Statement " + currency)
                    .titleMerged(title, 13)
                    .meta("Producer", producerLabel)
                    .meta("Period",   periodStart + " to " + periodEnd)
                    .meta("Currency", currency);
            sheet.blankRow();
            sheet.header("Reference", "Producer code", "Producer", "Home ccy",
                    "Contribution #", "Member #", "Line", "Rate card", "Applied %",
                    "Contribution amt", "Commission (native)", "Native ccy",
                    "Status", "Occurred");
            sheet.forEach(sheetRows, (sw, r) -> sw
                    .text(safe(r.reference()))
                    .text(safe(r.producerCode()))
                    .text(safe(r.producerName()))
                    .text(safe(r.producerHomeCurrency()))
                    .text(shortId(r.contributionId()))
                    .text(shortId(r.memberId()))
                    .text(safe(r.insuranceLine()))
                    .text(safe(r.rateCardName()))
                    .money(r.appliedRatePct())
                    .money(r.contributionAmount())
                    .money(r.nativeAmount())
                    .text(safe(r.nativeCurrency()))
                    .text(safe(r.status()))
                    .date(r.occurredAt() != null ? r.occurredAt().toInstant() : null));
            sheet.blankRow();
            sheet.metaMoney("Total (" + currency + ")", sheetTotal);
            sheet.freezeAtHeader().autoSize();
        }
        buildSummarySheet(book, title, producerLabel,
                periodStart, periodEnd, byCurrency,
                CommissionStatementRow::nativeAmount,
                reportingCurrency, convertedGrandTotal);
        return book.toBytes();
    }

    // ── Clawback register ───────────────────────────────────────────────────

    public Mono<byte[]> clawbackWorkbook(LocalDate periodStart, LocalDate periodEnd,
                                         UUID producerId, String source,
                                         String overrideCurrency, UUID tenantId) {
        OffsetDateTime from = periodStart.atStartOfDay().atOffset(ZoneOffset.UTC);
        OffsetDateTime to   = periodEnd.plusDays(1).atStartOfDay().atOffset(ZoneOffset.UTC);

        Mono<String> producerName = producerId != null
                ? producerRepository.findById(producerId)
                        .map(p -> p.getName() + " (" + p.getProducerCode() + ")")
                        .defaultIfEmpty("Producer " + producerId)
                : Mono.just("All producers");

        return currencyResolver.resolve(tenantId, overrideCurrency)
                .flatMap(reportingCurrency -> Mono.zip(
                        producerName,
                        queryRepository.clawbackRows(from, to, producerId, source).collectList()
                ).flatMap(tuple -> {
                    String pName = tuple.getT1();
                    List<ClawbackRegisterRow> rows = tuple.getT2();

                    // Grouping is by source for the register — one sheet per
                    // trigger family. Currency subtotals move to the Summary
                    // where the reader wants the ledger view.
                    Map<String, List<ClawbackRegisterRow>> bySource =
                            groupBy(rows, ClawbackRegisterRow::source);
                    Map<String, List<ClawbackRegisterRow>> byCurrency =
                            groupBy(rows, ClawbackRegisterRow::nativeCurrency);
                    return convertGrandTotalMulti(byCurrency,
                                    ClawbackRegisterRow::nativeAmount,
                                    reportingCurrency, periodEnd, tenantId)
                            .map(converted -> renderClawbackBook(periodStart, periodEnd,
                                    pName, bySource, byCurrency, reportingCurrency, converted))
                            .defaultIfEmpty(renderClawbackBook(periodStart, periodEnd,
                                    pName, bySource, byCurrency, reportingCurrency, null));
                }));
    }

    private byte[] renderClawbackBook(LocalDate periodStart, LocalDate periodEnd,
                                      String producerLabel,
                                      Map<String, List<ClawbackRegisterRow>> bySource,
                                      Map<String, List<ClawbackRegisterRow>> byCurrency,
                                      String reportingCurrency,
                                      BigDecimal convertedGrandTotal) {
        ReportWorkbook book = ReportWorkbook.newBook();
        String title = "Clawback Register — " + periodStart + " to " + periodEnd;

        List<String> sources = new ArrayList<>(bySource.keySet());
        Collections.sort(sources);
        if (sources.isEmpty()) {
            book.sheet("Empty")
                    .titleMerged(title, 12)
                    .meta("Producer", producerLabel)
                    .meta("Period",   periodStart + " to " + periodEnd)
                    .meta("Note",     "No clawback events in this period.")
                    .freezeAtHeader().autoSize();
        }
        for (String source : sources) {
            List<ClawbackRegisterRow> sheetRows = bySource.get(source);

            ReportWorkbook.SheetWriter sheet = book.sheet("Clawback " + source)
                    .titleMerged(title, 12)
                    .meta("Producer", producerLabel)
                    .meta("Period",   periodStart + " to " + periodEnd)
                    .meta("Source",   source);
            sheet.blankRow();
            sheet.header("Source", "Trigger ref", "Producer code", "Producer",
                    "Commission ref", "Member #",
                    "Amount (native)", "Native ccy", "Reason", "Occurred");
            sheet.forEach(sheetRows, (sw, r) -> sw
                    .text(safe(r.source()))
                    .text(safe(r.triggeringEventRef()))
                    .text(safe(r.producerCode()))
                    .text(safe(r.producerName()))
                    .text(safe(r.commissionReference()))
                    .text(shortId(r.memberId()))
                    .money(r.nativeAmount())
                    .text(safe(r.nativeCurrency()))
                    .text(safe(r.reason()))
                    .date(r.occurredAt() != null ? r.occurredAt().toInstant() : null));
            sheet.blankRow();
            // Per-currency subtotal within this source sheet, to help auditors
            // reconcile against the summary at a glance.
            Map<String, List<ClawbackRegisterRow>> perCcyInSource =
                    groupBy(sheetRows, ClawbackRegisterRow::nativeCurrency);
            List<String> ccys = new ArrayList<>(perCcyInSource.keySet());
            Collections.sort(ccys);
            for (String ccy : ccys) {
                BigDecimal subtotal = sumBigDecimal(perCcyInSource.get(ccy),
                        ClawbackRegisterRow::nativeAmount);
                sheet.metaMoney("Subtotal (" + ccy + ")", subtotal);
            }
            sheet.freezeAtHeader().autoSize();
        }
        buildSummarySheet(book, title, producerLabel,
                periodStart, periodEnd, byCurrency,
                ClawbackRegisterRow::nativeAmount,
                reportingCurrency, convertedGrandTotal);
        return book.toBytes();
    }

    // ── Summary sheet ───────────────────────────────────────────────────────

    private <T> void buildSummarySheet(ReportWorkbook book, String title,
                                       String producerLabel,
                                       LocalDate periodStart, LocalDate periodEnd,
                                       Map<String, List<T>> byCurrency,
                                       Function<T, BigDecimal> amountFn,
                                       String reportingCurrency,
                                       BigDecimal convertedGrandTotal) {
        LocalDate asOf = periodEnd;
        ReportWorkbook.SheetWriter sheet = book.sheet("Summary")
                .titleMerged(title + " — Summary", 3)
                .meta("Producer", producerLabel)
                .meta("Period",   periodStart + " to " + periodEnd)
                .meta("As of",    asOf.toString());
        sheet.blankRow();

        List<String> currencies = new ArrayList<>(byCurrency.keySet());
        Collections.sort(currencies);
        sheet.header("Currency", "Total (native)", "Note");
        for (String currency : currencies) {
            BigDecimal total = sumBigDecimal(byCurrency.get(currency), amountFn);
            sheet.text(currency).moneyBold(total).text("Native total");
            sheet.nextRow();
        }
        sheet.blankRow();
        if (convertedGrandTotal != null) {
            sheet.metaMoney("Converted grand total (" + reportingCurrency + " @ " + asOf + ")",
                    convertedGrandTotal);
        } else {
            sheet.meta("Converted grand total (" + reportingCurrency + ")",
                    "FX unavailable for one or more currencies at " + asOf
                            + " — converted total omitted");
        }
        sheet.freezeAtHeader().autoSize();
    }

    // ── FX conversion helpers ───────────────────────────────────────────────

    /**
     * Sum each per-currency bucket in native, convert each subtotal to
     * {@code reportingCurrency}, sum the results. If ANY conversion is
     * missing a rate returns empty — the Summary then renders the "FX
     * unavailable" fallback line instead of a partial number.
     */
    private <T> Mono<BigDecimal> convertGrandTotalMulti(
            Map<String, List<T>> byCurrency,
            Function<T, BigDecimal> amountFn,
            String reportingCurrency, LocalDate asOf, UUID tenantId) {
        if (byCurrency.isEmpty()) return Mono.just(BigDecimal.ZERO);
        return Flux.fromIterable(byCurrency.entrySet())
                .flatMap(e -> {
                    BigDecimal nativeSum = sumBigDecimal(e.getValue(), amountFn);
                    return fxConverter.convert(nativeSum, e.getKey(), reportingCurrency, asOf, tenantId)
                            .onErrorResume(err -> {
                                log.debug("[commission-workbook] FX {}->{} at {} unavailable: {}",
                                        e.getKey(), reportingCurrency, asOf, err.getMessage());
                                return Mono.empty();
                            });
                })
                .collectList()
                .flatMap(subtotals -> subtotals.size() == byCurrency.size()
                        ? Mono.just(subtotals.stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                        : Mono.empty());
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    private static <T> Map<String, List<T>> groupBy(List<T> rows, Function<T, String> keyFn) {
        Map<String, List<T>> grouped = new LinkedHashMap<>();
        for (T row : rows) {
            String key = keyFn.apply(row);
            if (key == null) key = "—";
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

    private static String safe(String s) {
        return s != null ? s : "";
    }
}
