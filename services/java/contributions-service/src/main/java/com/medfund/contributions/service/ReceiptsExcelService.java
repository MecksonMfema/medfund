package com.medfund.contributions.service;

import com.medfund.contributions.dto.PageResponse;
import com.medfund.contributions.dto.ReceiptsDetailResponse;
import com.medfund.contributions.dto.ReceiptsSummaryRow;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportWorkbook;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * XLSX generator for the receipts-report family. Rows stay native-currency
 * per G25; when a reporting currency is passed the workbook adds a rightmost
 * "Amount in {reportingCurrency}" column populated via
 * {@link FxRateReader#findRate}. Missing FX rates skip the extra column for
 * that currency rather than failing the export.
 *
 * <p>Detail exports use two sheets (monthly buckets + transaction ledger)
 * per G40 / F29; the ledger sheet is capped at 10k rows.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReceiptsExcelService {

    private static final int ROW_CEILING = 10_000;

    private final ReceiptsReportService receiptsReportService;
    private final FxRateReader fxRateReader;

    // ── Summary exports ────────────────────────────────────────────────────

    public Mono<byte[]> schemesReportExcel(LocalDate periodStart, LocalDate periodEnd, String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return receiptsReportService.perSchemeSummary(periodStart, periodEnd)
                    .flatMap(rows -> renderSummaryWorkbook(rows,
                            "Receipts by scheme", "Scheme",
                            periodStart, periodEnd, reportingCurrency, tenantId));
        });
    }

    public Mono<byte[]> groupsReportExcel(LocalDate periodStart, LocalDate periodEnd, String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return receiptsReportService.perGroupSummary(periodStart, periodEnd)
                    .flatMap(rows -> renderSummaryWorkbook(rows,
                            "Receipts by group", "Group",
                            periodStart, periodEnd, reportingCurrency, tenantId));
        });
    }

    public Mono<byte[]> membersReportExcel(LocalDate periodStart, LocalDate periodEnd,
                                            String search, String insuranceLine, UUID schemeId,
                                            String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            // Fetch the full unpaged set for export (capped by ROW_CEILING).
            return receiptsReportService.perMemberSummary(periodStart, periodEnd,
                            search, insuranceLine, schemeId, 0, ROW_CEILING)
                    .flatMap(page -> {
                        if (page.total() > ROW_CEILING) {
                            return Mono.error(new IllegalArgumentException(
                                    "Row count " + page.total() + " exceeds " + ROW_CEILING
                                            + " — refine filters (search / insurance line / scheme)."));
                        }
                        return renderSummaryWorkbook(page.content(),
                                "Receipts by member", "Member",
                                periodStart, periodEnd, reportingCurrency, tenantId);
                    });
        });
    }

    // ── Detail export (drill-down) ─────────────────────────────────────────

    public Mono<byte[]> detailExcel(String dimension, UUID id, String dimensionLabel,
                                     LocalDate periodStart, LocalDate periodEnd,
                                     String transactionType, String currencyCode,
                                     String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            // Cap at ROW_CEILING — one page grab, exports never paginate.
            return receiptsReportService.detail(dimension, id, periodStart, periodEnd,
                            transactionType, currencyCode, 0, ROW_CEILING)
                    .flatMap(detail -> renderDetailWorkbook(detail, dimensionLabel,
                            periodStart, periodEnd, reportingCurrency, tenantId));
        });
    }

    public Mono<byte[]> unallocatedDetailExcel(LocalDate periodStart, LocalDate periodEnd,
                                                String transactionType, String currencyCode,
                                                String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return receiptsReportService.unallocatedDetail(periodStart, periodEnd,
                            transactionType, currencyCode, 0, ROW_CEILING)
                    .flatMap(detail -> renderDetailWorkbook(detail, "Unallocated group payments",
                            periodStart, periodEnd, reportingCurrency, tenantId));
        });
    }

    // ── Workbook rendering ─────────────────────────────────────────────────

    private Mono<byte[]> renderSummaryWorkbook(List<ReceiptsSummaryRow> rows,
                                                String sheetName, String dimensionLabel,
                                                LocalDate periodStart, LocalDate periodEnd,
                                                String reportingCurrency, UUID tenantId) {
        if (rows.size() > ROW_CEILING) {
            return Mono.error(new IllegalArgumentException(
                    "Row count " + rows.size() + " exceeds " + ROW_CEILING
                            + " — narrow the period or filter."));
        }
        boolean hasInsuranceLine = rows.stream().anyMatch(r -> r.insuranceLine() != null && !r.insuranceLine().isBlank());
        return loadFxRates(rows, ReceiptsSummaryRow::currencyCode, reportingCurrency, tenantId, periodEnd)
                .map(fx -> {
                    boolean converted = reportingCurrency != null && !reportingCurrency.isBlank();
                    int baseCols = hasInsuranceLine ? 6 : 5;
                    int spanCols = converted ? baseCols + 1 : baseCols;

                    ReportWorkbook.SheetWriter sheet = ReportWorkbook.newBook()
                            .sheet(sheetName)
                            .titleMerged("Receipts report — per " + dimensionLabel.toLowerCase(), spanCols)
                            .meta("Period start", periodStart != null ? periodStart.toString() : "—")
                            .meta("Period end",   periodEnd   != null ? periodEnd.toString()   : "—")
                            .meta("Reporting currency", converted ? reportingCurrency : "(native)")
                            .meta("Rows", String.valueOf(rows.size()))
                            .blankRow();

                    if (hasInsuranceLine) {
                        if (converted) {
                            sheet.header(dimensionLabel, "Line", "Currency",
                                    "Net received", "Transactions",
                                    "Net received (" + reportingCurrency + ")");
                        } else {
                            sheet.header(dimensionLabel, "Line", "Currency", "Net received", "Transactions");
                        }
                        sheet.forEach(rows, (sw, row) -> {
                            sw.text(row.dimensionName())
                                    .text(row.insuranceLine())
                                    .text(row.currencyCode())
                                    .moneyBold(row.totalReceived())
                                    .number(row.transactionCount());
                            if (converted) sw.money(convert(row.totalReceived(), row.currencyCode(), fx));
                        });
                    } else {
                        if (converted) {
                            sheet.header(dimensionLabel, "Currency",
                                    "Net received", "Transactions",
                                    "Net received (" + reportingCurrency + ")");
                        } else {
                            sheet.header(dimensionLabel, "Currency", "Net received", "Transactions");
                        }
                        sheet.forEach(rows, (sw, row) -> {
                            sw.text(row.dimensionName())
                                    .text(row.currencyCode())
                                    .moneyBold(row.totalReceived())
                                    .number(row.transactionCount());
                            if (converted) sw.money(convert(row.totalReceived(), row.currencyCode(), fx));
                        });
                    }

                    return sheet.freezeAtHeader().autoSize().toBytes();
                });
    }

    private Mono<byte[]> renderDetailWorkbook(ReceiptsDetailResponse detail, String dimensionLabel,
                                               LocalDate periodStart, LocalDate periodEnd,
                                               String reportingCurrency, UUID tenantId) {
        PageResponse<ReceiptsDetailResponse.TransactionLedgerRow> ledger = detail.transactions();
        if (ledger != null && ledger.total() > ROW_CEILING) {
            return Mono.error(new IllegalArgumentException(
                    "Ledger row count " + ledger.total() + " exceeds " + ROW_CEILING
                            + " — refine filters (month / type / currency)."));
        }

        List<ReceiptsDetailResponse.MonthlyBucket> buckets =
                detail.monthlyBuckets() != null ? detail.monthlyBuckets() : List.of();
        List<ReceiptsDetailResponse.TransactionLedgerRow> rows =
                ledger != null && ledger.content() != null ? ledger.content() : List.of();

        return loadFxRates(rows, ReceiptsDetailResponse.TransactionLedgerRow::currencyCode,
                        reportingCurrency, tenantId, periodEnd)
                .map(fx -> {
                    boolean converted = reportingCurrency != null && !reportingCurrency.isBlank();

                    ReportWorkbook book = ReportWorkbook.newBook();

                    // Sheet 1 — monthly summary
                    ReportWorkbook.SheetWriter monthlySheet = book.sheet("Monthly buckets")
                            .titleMerged("Receipts by month — " + safeName(detail.dimensionName()), 4)
                            .meta("Dimension", dimensionLabel)
                            .meta("Period start", periodStart != null ? periodStart.toString() : "—")
                            .meta("Period end",   periodEnd   != null ? periodEnd.toString()   : "—")
                            .blankRow()
                            .header("Month", "Currency", "Net received", "Transactions");
                    monthlySheet.forEach(buckets, (sw, b) -> sw
                            .date(b.month())
                            .text(b.currencyCode())
                            .moneyBold(b.totalReceived())
                            .number(b.transactionCount()));
                    monthlySheet.freezeAtHeader().autoSize();

                    // Sheet 2 — full transaction ledger
                    int span = converted ? 8 : 7;
                    ReportWorkbook.SheetWriter ledgerSheet = book.sheet("Transaction ledger")
                            .titleMerged("Transaction ledger — " + safeName(detail.dimensionName()), span)
                            .meta("Rows", String.valueOf(rows.size()))
                            .meta("Reporting currency", converted ? reportingCurrency : "(native)")
                            .blankRow();

                    if (converted) {
                        ledgerSheet.header("Date", "Number", "Type", "Method",
                                "Reference", "Amount", "Currency",
                                "Amount (" + reportingCurrency + ")");
                    } else {
                        ledgerSheet.header("Date", "Number", "Type", "Method",
                                "Reference", "Amount", "Currency");
                    }

                    ledgerSheet.forEach(rows, (sw, row) -> {
                        sw.date(row.transactionDate())
                                .text(row.transactionNumber())
                                .text(row.transactionType())
                                .text(row.paymentMethod())
                                .text(row.reference())
                                .moneyBold(row.amount())
                                .text(row.currencyCode());
                        if (converted) sw.money(convert(row.amount(), row.currencyCode(), fx));
                    });

                    return ledgerSheet.freezeAtHeader().autoSize().toBytes();
                });
    }

    // ── FX helpers ─────────────────────────────────────────────────────────

    private <R> Mono<Map<String, BigDecimal>> loadFxRates(List<R> rows,
                                                          java.util.function.Function<R, String> currencyExtractor,
                                                          String reportingCurrency, UUID tenantId, LocalDate asOf) {
        if (reportingCurrency == null || reportingCurrency.isBlank() || rows.isEmpty()) {
            return Mono.just(Map.of());
        }
        Map<String, BigDecimal> rates = new HashMap<>();
        return Flux.fromIterable(rows)
                .map(currencyExtractor)
                .filter(ccy -> ccy != null && !ccy.isBlank())
                .distinct()
                .flatMap(ccy -> fxRateReader.findRate(ccy, reportingCurrency, asOf, tenantId)
                        .doOnNext(rate -> rates.put(ccy, rate))
                        .then(Mono.just(ccy)))
                .then(Mono.fromSupplier(() -> Map.copyOf(rates)));
    }

    private static BigDecimal convert(BigDecimal amount, String currency, Map<String, BigDecimal> fx) {
        if (amount == null) return null;
        BigDecimal rate = fx.get(currency);
        return rate != null ? amount.multiply(rate) : null;
    }

    private static String safeName(String name) {
        return name != null && !name.isBlank() ? name : "—";
    }

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try { return UUID.fromString(tenantIdStr); } catch (IllegalArgumentException e) { return null; }
    }
}
