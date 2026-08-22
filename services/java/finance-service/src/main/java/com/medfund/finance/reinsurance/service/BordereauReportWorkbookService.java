package com.medfund.finance.reinsurance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.reinsurance.dto.CessionBordereauRow;
import com.medfund.finance.reinsurance.dto.RecoveriesBordereauRow;
import com.medfund.finance.reinsurance.dto.TreatyUtilizationRow;
import com.medfund.finance.reinsurance.repository.BordereauQueryRepository;
import com.medfund.finance.reinsurance.repository.ReinsurerRepository;
import com.medfund.finance.reinsurance.repository.TreatyRepository;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportPeriod;
import com.medfund.shared.report.ReportWorkbook;
import com.medfund.shared.report.ReportingCurrencyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Multi-sheet XLSX renderer for the three bordereau reports. Same shape as
 * {@code PaymentRunWorkbookService}: one sheet per distinct currency present
 * in the rows, plus a summary sheet with per-currency native totals and a
 * best-effort converted grand total in the tenant reporting currency
 * (falling back to a warning line when FX is unavailable — R7 / D7-3).
 *
 * <p>Rows are participant-split (R9) at query time —
 * {@code cededAmount × sharePct / 100} — so a treaty with four reinsurers
 * appears as four rows per cession. Grand-total conversion uses the
 * participant-share amount, not the full cession amount.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BordereauReportWorkbookService {

    private final BordereauQueryRepository queryRepository;
    private final BordereauPeriodExportService periodExportService;
    private final ReinsurerRepository reinsurerRepository;
    private final TreatyRepository treatyRepository;
    private final ReportingCurrencyResolver currencyResolver;
    private final FxConverter fxConverter;

    // ── Cession bordereau ───────────────────────────────────────────────────

    public Mono<byte[]> cessionWorkbook(UUID reinsurerId, UUID treatyId,
                                        int year, int quarter,
                                        String overrideCurrency, UUID tenantId) {
        ReportPeriod period = BordereauReportService.quarterToPeriod(year, quarter);
        LocalDate exclusiveEnd = period.periodEnd().plusDays(1);

        Mono<String> reinsurerName = reinsurerId != null
                ? reinsurerRepository.findById(reinsurerId).map(r -> r.getName())
                        .defaultIfEmpty("Reinsurer " + reinsurerId)
                : Mono.just("All reinsurers");
        Mono<String> treatyRef = treatyId != null
                ? treatyRepository.findById(treatyId).map(t -> t.getTreatyRef())
                        .defaultIfEmpty("Treaty " + treatyId)
                : Mono.just("All treaties");

        return currencyResolver.resolve(tenantId, overrideCurrency)
                .flatMap(reportingCurrency -> Mono.zip(
                        reinsurerName,
                        treatyRef,
                        periodExportService.firstExportedAt(reinsurerId, treatyId,
                                        ReportKey.REINSURANCE_CESSION_BORDEREAU.name(),
                                        year, quarter)
                                .defaultIfEmpty(OffsetDateTime.MAX),
                        queryRepository.cessionRows(reinsurerId, treatyId,
                                        period.periodStart(), exclusiveEnd)
                                .collectList()
                ).flatMap(tuple -> {
                    String rName = tuple.getT1();
                    String tRef  = tuple.getT2();
                    OffsetDateTime firstExportedAt = tuple.getT3();
                    List<CessionBordereauRow> raw = tuple.getT4();

                    List<CessionBordereauRow> rows = raw.stream()
                            .map(r -> r.withPriorPeriodAdjustment(
                                    r.createdAt() != null && firstExportedAt != OffsetDateTime.MAX
                                            && r.createdAt().isAfter(firstExportedAt)))
                            .toList();

                    Map<String, List<CessionBordereauRow>> byCurrency =
                            groupBy(rows, CessionBordereauRow::currencyCode);
                    LocalDate asOf = period.periodEnd();
                    return convertGrandTotalMulti(byCurrency, CessionBordereauRow::participantCeded,
                                    reportingCurrency, asOf, tenantId)
                            .map(converted -> renderCessionBook(period, year, quarter,
                                    rName, tRef, byCurrency, reportingCurrency, asOf, converted))
                            .defaultIfEmpty(renderCessionBook(period, year, quarter,
                                    rName, tRef, byCurrency, reportingCurrency, asOf, null));
                }));
    }

    private byte[] renderCessionBook(ReportPeriod period, int year, int quarter,
                                     String reinsurerName, String treatyRef,
                                     Map<String, List<CessionBordereauRow>> byCurrency,
                                     String reportingCurrency, LocalDate asOf,
                                     BigDecimal convertedGrandTotal) {
        ReportWorkbook book = ReportWorkbook.newBook();

        List<String> currencies = new ArrayList<>(byCurrency.keySet());
        Collections.sort(currencies);
        if (currencies.isEmpty()) {
            emptyPlaceholderSheet(book, "Cession Bordereau — Q" + quarter + " " + year,
                    reinsurerName, treatyRef, period);
        }
        for (String currency : currencies) {
            List<CessionBordereauRow> sheetRows = byCurrency.get(currency);
            BigDecimal sheetTotal = sumParticipantCeded(sheetRows);

            ReportWorkbook.SheetWriter sheet = book.sheet("Cessions " + currency)
                    .titleMerged("Cession Bordereau — Q" + quarter + " " + year, 13)
                    .meta("Reinsurer", reinsurerName)
                    .meta("Treaty",    treatyRef)
                    .meta("Period",    period.periodStart() + " to " + period.periodEnd())
                    .meta("Currency",  currency);
            sheet.blankRow();
            sheet.header("Cession #", "Treaty", "Type", "Reinsurer", "Share %",
                    "Cession type", "Source", "Occurred", "Source event",
                    "Basis (native)", "Ceded (native)", "Participant share",
                    "Prior-period adj");
            sheet.forEach(sheetRows, (sw, r) -> sw
                    .text(shortId(r.cessionId()))
                    .text(safe(r.treatyRef()))
                    .text(safe(r.treatyType()))
                    .text(safe(r.reinsurerName()))
                    .money(r.sharePct())
                    .text(safe(r.cessionType()))
                    .text(safe(r.source()))
                    .date(r.occurredAt() != null ? r.occurredAt().toInstant() : null)
                    .text(shortId(r.sourceEventId()))
                    .money(r.nativeBasis())
                    .money(r.nativeCededFull())
                    .money(r.participantCeded())
                    .text(r.priorPeriodAdjustment() ? "YES" : ""));
            sheet.blankRow();
            sheet.metaMoney("Total (" + currency + ")", sheetTotal);
            sheet.freezeAtHeader().autoSize();
        }
        buildSummarySheet(book, "Cession Bordereau — Q" + quarter + " " + year,
                reinsurerName, treatyRef, period, byCurrency,
                CessionBordereauRow::participantCeded,
                reportingCurrency, asOf, convertedGrandTotal);
        return book.toBytes();
    }

    // ── Recoveries bordereau ────────────────────────────────────────────────

    public Mono<byte[]> recoveriesWorkbook(UUID reinsurerId, UUID treatyId,
                                           int year, int quarter,
                                           String overrideCurrency, UUID tenantId) {
        ReportPeriod period = BordereauReportService.quarterToPeriod(year, quarter);
        LocalDate exclusiveEnd = period.periodEnd().plusDays(1);

        Mono<String> reinsurerName = reinsurerId != null
                ? reinsurerRepository.findById(reinsurerId).map(r -> r.getName())
                        .defaultIfEmpty("Reinsurer " + reinsurerId)
                : Mono.just("All reinsurers");
        Mono<String> treatyRef = treatyId != null
                ? treatyRepository.findById(treatyId).map(t -> t.getTreatyRef())
                        .defaultIfEmpty("Treaty " + treatyId)
                : Mono.just("All treaties");

        return currencyResolver.resolve(tenantId, overrideCurrency)
                .flatMap(reportingCurrency -> Mono.zip(
                        reinsurerName,
                        treatyRef,
                        periodExportService.firstExportedAt(reinsurerId, treatyId,
                                        ReportKey.REINSURANCE_RECOVERIES.name(),
                                        year, quarter)
                                .defaultIfEmpty(OffsetDateTime.MAX),
                        queryRepository.recoveriesRows(reinsurerId, treatyId,
                                        period.periodStart(), exclusiveEnd)
                                .collectList()
                ).flatMap(tuple -> {
                    String rName = tuple.getT1();
                    String tRef  = tuple.getT2();
                    OffsetDateTime firstExportedAt = tuple.getT3();
                    List<RecoveriesBordereauRow> raw = tuple.getT4();

                    List<RecoveriesBordereauRow> rows = raw.stream()
                            .map(r -> r.withPriorPeriodAdjustment(
                                    r.createdAt() != null && firstExportedAt != OffsetDateTime.MAX
                                            && r.createdAt().isAfter(firstExportedAt)))
                            .toList();

                    Map<String, List<RecoveriesBordereauRow>> byCurrency =
                            groupBy(rows, RecoveriesBordereauRow::currencyCode);
                    LocalDate asOf = period.periodEnd();
                    return convertGrandTotalMulti(byCurrency, RecoveriesBordereauRow::participantExpected,
                                    reportingCurrency, asOf, tenantId)
                            .map(converted -> renderRecoveriesBook(period, year, quarter,
                                    rName, tRef, byCurrency, reportingCurrency, asOf, converted))
                            .defaultIfEmpty(renderRecoveriesBook(period, year, quarter,
                                    rName, tRef, byCurrency, reportingCurrency, asOf, null));
                }));
    }

    private byte[] renderRecoveriesBook(ReportPeriod period, int year, int quarter,
                                        String reinsurerName, String treatyRef,
                                        Map<String, List<RecoveriesBordereauRow>> byCurrency,
                                        String reportingCurrency, LocalDate asOf,
                                        BigDecimal convertedGrandTotal) {
        ReportWorkbook book = ReportWorkbook.newBook();

        List<String> currencies = new ArrayList<>(byCurrency.keySet());
        Collections.sort(currencies);
        if (currencies.isEmpty()) {
            emptyPlaceholderSheet(book, "Recoveries Bordereau — Q" + quarter + " " + year,
                    reinsurerName, treatyRef, period);
        }
        for (String currency : currencies) {
            List<RecoveriesBordereauRow> sheetRows = byCurrency.get(currency);
            BigDecimal sheetExpectedTotal = sumBigDecimal(sheetRows, RecoveriesBordereauRow::participantExpected);
            BigDecimal sheetReceivedTotal = sumBigDecimal(sheetRows, RecoveriesBordereauRow::participantReceived);

            ReportWorkbook.SheetWriter sheet = book.sheet("Recoveries " + currency)
                    .titleMerged("Recoveries Bordereau — Q" + quarter + " " + year, 13)
                    .meta("Reinsurer", reinsurerName)
                    .meta("Treaty",    treatyRef)
                    .meta("Period",    period.periodStart() + " to " + period.periodEnd())
                    .meta("Currency",  currency);
            sheet.blankRow();
            sheet.header("Recovery #", "Cession", "Treaty", "Reinsurer", "Share %",
                    "Status", "Source event", "Occurred",
                    "Expected (native)", "Received (native)",
                    "Participant expected", "Participant received",
                    "Prior-period adj");
            sheet.forEach(sheetRows, (sw, r) -> sw
                    .text(shortId(r.recoveryId()))
                    .text(shortId(r.cessionId()))
                    .text(safe(r.treatyRef()))
                    .text(safe(r.reinsurerName()))
                    .money(r.sharePct())
                    .text(safe(r.status()))
                    .text(shortId(r.sourceEventId()))
                    .date(r.occurredAt() != null ? r.occurredAt().toInstant() : null)
                    .money(r.nativeExpected())
                    .money(r.nativeReceived())
                    .money(r.participantExpected())
                    .money(r.participantReceived())
                    .text(r.priorPeriodAdjustment() ? "YES" : ""));
            sheet.blankRow();
            sheet.metaMoney("Expected total (" + currency + ")", sheetExpectedTotal);
            sheet.metaMoney("Received total (" + currency + ")", sheetReceivedTotal);
            sheet.freezeAtHeader().autoSize();
        }
        buildSummarySheet(book, "Recoveries Bordereau — Q" + quarter + " " + year,
                reinsurerName, treatyRef, period, byCurrency,
                RecoveriesBordereauRow::participantExpected,
                reportingCurrency, asOf, convertedGrandTotal);
        return book.toBytes();
    }

    // ── Treaty utilization ──────────────────────────────────────────────────

    public Mono<byte[]> utilizationWorkbook(UUID treatyId, LocalDate asOfDate,
                                            String overrideCurrency, UUID tenantId) {
        LocalDate resolvedAsOf = asOfDate != null ? asOfDate : LocalDate.now();
        Mono<String> treatyRef = treatyRepository.findById(treatyId)
                .map(t -> t.getTreatyRef())
                .defaultIfEmpty("Treaty " + treatyId);

        return currencyResolver.resolve(tenantId, overrideCurrency)
                .flatMap(reportingCurrency -> Mono.zip(
                        treatyRef,
                        queryRepository.utilizationRows(treatyId, resolvedAsOf).collectList()
                ).flatMap(tuple -> {
                    String tRef = tuple.getT1();
                    List<TreatyUtilizationRow> rows = tuple.getT2();

                    Map<String, List<TreatyUtilizationRow>> byCurrency =
                            groupBy(rows, r -> r.cededCurrency() != null
                                    ? r.cededCurrency()
                                    : (r.layerCurrency() != null ? r.layerCurrency() : "—"));
                    return convertGrandTotalMulti(byCurrency, TreatyUtilizationRow::totalCededNative,
                                    reportingCurrency, resolvedAsOf, tenantId)
                            .map(converted -> renderUtilizationBook(treatyId, tRef, resolvedAsOf,
                                    byCurrency, reportingCurrency, converted))
                            .defaultIfEmpty(renderUtilizationBook(treatyId, tRef, resolvedAsOf,
                                    byCurrency, reportingCurrency, null));
                }));
    }

    private byte[] renderUtilizationBook(UUID treatyId, String treatyRef, LocalDate asOf,
                                         Map<String, List<TreatyUtilizationRow>> byCurrency,
                                         String reportingCurrency,
                                         BigDecimal convertedGrandTotal) {
        ReportWorkbook book = ReportWorkbook.newBook();

        List<String> currencies = new ArrayList<>(byCurrency.keySet());
        Collections.sort(currencies);
        if (currencies.isEmpty()) {
            ReportWorkbook.SheetWriter empty = book.sheet("Utilization")
                    .titleMerged("Treaty Utilization — " + treatyRef, 8)
                    .meta("Treaty",   treatyRef)
                    .meta("As of",    asOf.toString())
                    .meta("Note",     "No cessions written against this treaty yet.");
            empty.freezeAtHeader().autoSize();
        }
        for (String currency : currencies) {
            List<TreatyUtilizationRow> sheetRows = byCurrency.get(currency);
            BigDecimal sheetTotal = sumBigDecimal(sheetRows, TreatyUtilizationRow::totalCededNative);

            ReportWorkbook.SheetWriter sheet = book.sheet("Utilization " + currency)
                    .titleMerged("Treaty Utilization — " + treatyRef, 8)
                    .meta("Treaty",   treatyRef)
                    .meta("As of",    asOf.toString())
                    .meta("Currency", currency);
            sheet.blankRow();
            sheet.header("Layer", "Retention", "Layer limit", "Layer currency",
                    "Ceded currency", "Total ceded (native)", "Cession count", "Utilization %");
            sheet.forEach(sheetRows, (sw, r) -> {
                BigDecimal utilPct = utilizationPercent(r);
                sw.text(r.layerOrder() != null ? "L" + r.layerOrder() : "—")
                  .money(r.retention())
                  .money(r.layerLimit())
                  .text(safe(r.layerCurrency()))
                  .text(safe(r.cededCurrency()))
                  .money(r.totalCededNative())
                  .number(r.cessionCount())
                  .text(utilPct != null ? utilPct.toPlainString() + "%" : "n/a");
            });
            sheet.blankRow();
            sheet.metaMoney("Total ceded (" + currency + ")", sheetTotal);
            sheet.freezeAtHeader().autoSize();
        }
        buildUtilizationSummarySheet(book, treatyRef, asOf, byCurrency,
                reportingCurrency, convertedGrandTotal);
        return book.toBytes();
    }

    // ── Post-export INVOICED transition (R8) ────────────────────────────────

    /**
     * Snapshot the cession IDs whose recoveries are currently EXPECTED in
     * this quarter — the controller uses this list post-response to flip
     * them to INVOICED after the client has received the bytes. Called
     * before rendering so the snapshot reflects the state the bordereau
     * was rendered from.
     */
    public Mono<List<UUID>> snapshotExpectedRecoveryCessionIds(UUID reinsurerId, UUID treatyId,
                                                               int year, int quarter) {
        ReportPeriod period = BordereauReportService.quarterToPeriod(year, quarter);
        LocalDate exclusiveEnd = period.periodEnd().plusDays(1);
        return queryRepository.expectedRecoveryCessionIds(reinsurerId, treatyId,
                        period.periodStart(), exclusiveEnd)
                .collectList();
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private void emptyPlaceholderSheet(ReportWorkbook book, String title,
                                       String reinsurerName, String treatyRef,
                                       ReportPeriod period) {
        book.sheet("Empty")
                .titleMerged(title, 13)
                .meta("Reinsurer", reinsurerName)
                .meta("Treaty",    treatyRef)
                .meta("Period",    period.periodStart() + " to " + period.periodEnd())
                .meta("Note",      "No qualifying cessions in this period.")
                .freezeAtHeader().autoSize();
    }

    private <T> void buildSummarySheet(ReportWorkbook book, String title,
                                       String reinsurerName, String treatyRef,
                                       ReportPeriod period,
                                       Map<String, List<T>> byCurrency,
                                       java.util.function.Function<T, BigDecimal> amountFn,
                                       String reportingCurrency, LocalDate asOf,
                                       BigDecimal convertedGrandTotal) {
        ReportWorkbook.SheetWriter sheet = book.sheet("Summary")
                .titleMerged(title + " — Summary", 3)
                .meta("Reinsurer", reinsurerName)
                .meta("Treaty",    treatyRef)
                .meta("Period",    period.periodStart() + " to " + period.periodEnd())
                .meta("As of",     asOf.toString());
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

    private void buildUtilizationSummarySheet(ReportWorkbook book, String treatyRef,
                                              LocalDate asOf,
                                              Map<String, List<TreatyUtilizationRow>> byCurrency,
                                              String reportingCurrency,
                                              BigDecimal convertedGrandTotal) {
        ReportWorkbook.SheetWriter sheet = book.sheet("Summary")
                .titleMerged("Treaty Utilization — " + treatyRef + " — Summary", 3)
                .meta("Treaty", treatyRef)
                .meta("As of",  asOf.toString());
        sheet.blankRow();

        List<String> currencies = new ArrayList<>(byCurrency.keySet());
        Collections.sort(currencies);
        sheet.header("Currency", "Total ceded (native)", "Cession count");
        for (String currency : currencies) {
            List<TreatyUtilizationRow> rows = byCurrency.get(currency);
            BigDecimal total = sumBigDecimal(rows, TreatyUtilizationRow::totalCededNative);
            long cessionCount = rows.stream()
                    .mapToLong(r -> r.cessionCount() != null ? r.cessionCount() : 0L)
                    .sum();
            sheet.text(currency).moneyBold(total).number(cessionCount);
            sheet.nextRow();
        }
        sheet.blankRow();
        if (convertedGrandTotal != null) {
            sheet.metaMoney("Converted grand total (" + reportingCurrency + " @ " + asOf + ")",
                    convertedGrandTotal);
        } else {
            sheet.meta("Converted grand total (" + reportingCurrency + ")",
                    "FX unavailable at " + asOf + " — converted total omitted");
        }
        sheet.freezeAtHeader().autoSize();
    }

    /**
     * Sum each per-currency bucket in its native currency, then convert each
     * subtotal into {@code reportingCurrency} and sum the results.
     * If ANY conversion is missing an FX rate, returns empty and the summary
     * sheet renders the "FX unavailable" line instead of a partial number.
     */
    private <T> Mono<BigDecimal> convertGrandTotalMulti(
            Map<String, List<T>> byCurrency,
            java.util.function.Function<T, BigDecimal> amountFn,
            String reportingCurrency, LocalDate asOf, UUID tenantId) {
        if (byCurrency.isEmpty()) return Mono.just(BigDecimal.ZERO);
        return reactor.core.publisher.Flux.fromIterable(byCurrency.entrySet())
                .flatMap(e -> {
                    BigDecimal nativeSum = sumBigDecimal(e.getValue(), amountFn);
                    return fxConverter.convert(nativeSum, e.getKey(), reportingCurrency, asOf, tenantId)
                            .onErrorResume(err -> {
                                log.debug("[bordereau-workbook] FX {}->{} at {} unavailable: {}",
                                        e.getKey(), reportingCurrency, asOf, err.getMessage());
                                return Mono.empty();
                            });
                })
                .collectList()
                .flatMap(subtotals -> subtotals.size() == byCurrency.size()
                        ? Mono.just(subtotals.stream()
                                .reduce(BigDecimal.ZERO, BigDecimal::add))
                        : Mono.empty());
    }

    private static <T> Map<String, List<T>> groupBy(List<T> rows,
                                                    java.util.function.Function<T, String> keyFn) {
        Map<String, List<T>> byCurrency = new LinkedHashMap<>();
        for (T row : rows) {
            String key = keyFn.apply(row);
            if (key == null) key = "—";
            byCurrency.computeIfAbsent(key, k -> new ArrayList<>()).add(row);
        }
        return byCurrency;
    }

    private static <T> BigDecimal sumBigDecimal(List<T> rows,
                                                java.util.function.Function<T, BigDecimal> amountFn) {
        BigDecimal sum = BigDecimal.ZERO;
        for (T row : rows) {
            BigDecimal v = amountFn.apply(row);
            if (v != null) sum = sum.add(v);
        }
        return sum;
    }

    private static BigDecimal sumParticipantCeded(List<CessionBordereauRow> rows) {
        return sumBigDecimal(rows, CessionBordereauRow::participantCeded);
    }

    private static BigDecimal utilizationPercent(TreatyUtilizationRow r) {
        BigDecimal denom = r.layerLimit() != null ? r.layerLimit() : r.aggregateLimit();
        if (denom == null || denom.signum() == 0 || r.totalCededNative() == null) return null;
        return r.totalCededNative()
                .multiply(BigDecimal.valueOf(100))
                .divide(denom, 2, java.math.RoundingMode.HALF_UP);
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
