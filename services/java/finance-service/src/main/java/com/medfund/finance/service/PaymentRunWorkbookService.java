package com.medfund.finance.service;

import com.medfund.finance.client.FxConverter;
import com.medfund.finance.dto.PaymentRunWorkbookRow;
import com.medfund.finance.entity.PaymentRun;
import com.medfund.finance.repository.PaymentRunWorkbookQueryRepository;
import com.medfund.shared.report.ReportWorkbook;
import com.medfund.shared.report.ReportingCurrencyResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Multi-sheet XLSX for a payment run (Phase 7). One sheet per distinct
 * currency present in the run's items (D7-1 — today runs are single-currency
 * by construction, but the grouping future-proofs a multi-currency run),
 * plus a summary sheet with per-currency native totals and exactly one
 * converted total into the tenant reporting currency at the run's executed
 * date (D7-2). A missing FX rate omits the converted row and adds a warning
 * line instead of failing the export (D7-3).
 *
 * <p>Rows are native per-currency; cross-currency arithmetic only ever
 * happens through {@code FxConverter} (Rule 1 — never sum across
 * currencies).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentRunWorkbookService {

    private final PaymentRunWorkbookQueryRepository workbookQueryRepository;
    private final ReportingCurrencyResolver currencyResolver;
    private final FxConverter fxConverter;

    public Mono<byte[]> workbook(PaymentRun run, UUID tenantId) {
        return workbookQueryRepository.findByRunId(run.getId())
                .collectList()
                .flatMap(rows -> currencyResolver.resolve(tenantId, null)
                        .flatMap(reportingCurrency ->
                                render(run, rows, reportingCurrency, tenantId)));
    }

    private Mono<byte[]> render(PaymentRun run, List<PaymentRunWorkbookRow> rows,
                                String reportingCurrency, UUID tenantId) {
        Map<String, List<PaymentRunWorkbookRow>> byCurrency = groupByCurrency(rows);
        String runCurrency = run.getCurrencyCode();
        BigDecimal grandTotal = byCurrency.getOrDefault(runCurrency, List.of()).stream()
                .map(r -> r.amount() == null ? BigDecimal.ZERO : r.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        LocalDate asOf = asOfDate(run);
        return convert(grandTotal, runCurrency, reportingCurrency, asOf, tenantId)
                .map(converted -> renderBook(run, byCurrency, runCurrency, grandTotal,
                        reportingCurrency, asOf, converted))
                .defaultIfEmpty(renderBook(run, byCurrency, runCurrency, grandTotal,
                        reportingCurrency, asOf, null));
    }

    private byte[] renderBook(PaymentRun run,
                              Map<String, List<PaymentRunWorkbookRow>> byCurrency,
                              String runCurrency,
                              BigDecimal grandTotal,
                              String reportingCurrency,
                              LocalDate asOf,
                              BigDecimal converted) {
        ReportWorkbook book = ReportWorkbook.newBook();
        String runNumber = run.getRunNumber() != null ? run.getRunNumber() : "—";

        List<String> currencies = new ArrayList<>(byCurrency.keySet());
        java.util.Collections.sort(currencies);
        for (String currency : currencies) {
            List<PaymentRunWorkbookRow> sheetRows = byCurrency.get(currency);
            BigDecimal sheetTotal = sheetRows.stream()
                    .map(r -> r.amount() == null ? BigDecimal.ZERO : r.amount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            ReportWorkbook.SheetWriter sheet = book.sheet("Payment " + currency)
                    .titleMerged(runNumber + " — " + currency, 9)
                    .meta("Run number", runNumber)
                    .meta("Status", run.getStatus())
                    .meta("Payee type", run.getPayeeType() != null ? run.getPayeeType() : "—")
                    .meta("Payment count", String.valueOf(run.getPaymentCount() != null
                            ? run.getPaymentCount() : sheetRows.size()))
                    .meta("Executed at", run.getExecutedAt() != null
                            ? run.getExecutedAt().toString() : "—")
                    .meta("Currency", currency);
            sheet.blankRow();

            sheet.header("Payment #", "Payee", "Payee type", "Amount",
                    "Currency", "Status", "Payment method", "Reference", "Paid at");
            sheet.forEach(sheetRows, (sw, r) ->
                    sw.text(r.paymentNumber() != null ? r.paymentNumber() : "—")
                            .text(r.payeeName() != null && !r.payeeName().isBlank()
                                    ? r.payeeName() : "—")
                            .text(r.payeeType() != null ? r.payeeType() : "—")
                            .money(r.amount())
                            .text(r.currencyCode())
                            .text(r.status() != null ? r.status() : "—")
                            .text(r.paymentMethod() != null ? r.paymentMethod() : "—")
                            .text(r.reference() != null ? r.reference() : "—")
                            .date(r.paidAt()));
            sheet.blankRow();
            sheet.metaMoney("Total (" + currency + ")", sheetTotal);
            sheet.freezeAtHeader().autoSize();
        }

        buildSummarySheet(book, run, byCurrency, runCurrency, grandTotal,
                reportingCurrency, asOf, converted);
        return book.toBytes();
    }

    private void buildSummarySheet(ReportWorkbook book, PaymentRun run,
                                   Map<String, List<PaymentRunWorkbookRow>> byCurrency,
                                   String runCurrency,
                                   BigDecimal grandTotal,
                                   String reportingCurrency,
                                   LocalDate asOf,
                                   BigDecimal converted) {
        String summaryTitle = (run.getRunNumber() != null ? run.getRunNumber() : "—") + " — Summary";
        ReportWorkbook.SheetWriter sheet = book.sheet("Summary")
                .titleMerged(summaryTitle, 3)
                .meta("Run number", run.getRunNumber() != null ? run.getRunNumber() : "—")
                .meta("Status", run.getStatus())
                .meta("Payee type", run.getPayeeType() != null ? run.getPayeeType() : "—")
                .meta("Payment count", String.valueOf(run.getPaymentCount() != null
                        ? run.getPaymentCount() : 0))
                .meta("Executed at", run.getExecutedAt() != null
                        ? run.getExecutedAt().toString() : "—")
                .meta("Source currency", runCurrency != null ? runCurrency : "—");
        sheet.blankRow();

        List<String> currencies = new ArrayList<>(byCurrency.keySet());
        java.util.Collections.sort(currencies);
        sheet.header("Currency", "Total", "Note");
        for (String currency : currencies) {
            BigDecimal total = byCurrency.get(currency).stream()
                    .map(r -> r.amount() == null ? BigDecimal.ZERO : r.amount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            sheet.text(currency).moneyBold(total).text("Native total");
            sheet.nextRow();
        }
        sheet.blankRow();
        sheet.metaMoney("Grand total (" + (runCurrency != null ? runCurrency : "—") + ")",
                grandTotal);

        if (converted != null) {
            sheet.metaMoney("Converted to " + reportingCurrency + " (at " + asOf + ")",
                    converted);
        } else {
            sheet.meta("Converted to " + reportingCurrency,
                    "FX " + runCurrency + "→" + reportingCurrency
                            + " unavailable at " + asOf + " — converted total omitted");
        }
        sheet.freezeAtHeader().autoSize();
    }

    private Map<String, List<PaymentRunWorkbookRow>> groupByCurrency(List<PaymentRunWorkbookRow> rows) {
        Map<String, List<PaymentRunWorkbookRow>> byCurrency = new LinkedHashMap<>();
        for (PaymentRunWorkbookRow row : rows) {
            String currency = row.currencyCode() != null ? row.currencyCode() : "—";
            byCurrency.computeIfAbsent(currency, c -> new ArrayList<>()).add(row);
        }
        return byCurrency;
    }

    /** Conversion date: the run's executed date, falling back to its created
     *  date for un-executed runs (draft/approved/cancelled have no
     *  {@code executedAt} but still export per D7-7). */
    private LocalDate asOfDate(PaymentRun run) {
        Instant ref = run.getExecutedAt() != null ? run.getExecutedAt() : run.getCreatedAt();
        return ref != null ? ref.atZone(java.time.ZoneOffset.UTC).toLocalDate() : LocalDate.now();
    }

    private Mono<BigDecimal> convert(BigDecimal amount, String fromCurrency, String toCurrency,
                                     LocalDate asOf, UUID tenantId) {
        if (amount == null || amount.signum() == 0) {
            return Mono.just(BigDecimal.ZERO);
        }
        if (fromCurrency == null || fromCurrency.equalsIgnoreCase(toCurrency)) {
            return Mono.just(amount);
        }
        return fxConverter.convert(amount, fromCurrency, toCurrency, asOf, tenantId)
                .onErrorResume(err -> {
                    log.debug("[payment-run-workbook] FX {}->{} at {} unavailable: {}",
                            fromCurrency, toCurrency, asOf, err.getMessage());
                    return Mono.empty();
                });
    }
}
