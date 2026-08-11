package com.medfund.contributions.service;

import com.medfund.contributions.dto.GroupBillingSummaryRow;
import com.medfund.contributions.dto.MemberBillingSummaryRow;
import com.medfund.contributions.dto.SchemeBillingSummaryRow;
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
 * XLSX generator for the billing-report family. Rows stay native-currency
 * per G25; when a reporting currency is passed the workbook adds a rightmost
 * "Amount in {reportingCurrency}" column populated via
 * {@link FxRateReader#findRate}. Missing FX rates skip the extra column for
 * that currency rather than failing the export.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingReportExcelService {

    private static final int ROW_CEILING = 10_000;

    private final BillingReportService billingReportService;
    private final FxRateReader fxRateReader;

    public Mono<byte[]> schemeReportExcel(LocalDate periodStart, LocalDate periodEnd,
                                           String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseTenantId(tenantIdStr);
            return billingReportService.perSchemeSummary(periodStart, periodEnd)
                    .flatMap(rows -> {
                        if (rows.size() > ROW_CEILING) {
                            return Mono.error(new IllegalArgumentException(
                                    "Row count " + rows.size() + " exceeds " + ROW_CEILING
                                            + " — narrow the period or filter."));
                        }
                        return loadFxRates(rows, SchemeBillingSummaryRow::currencyCode,
                                reportingCurrency, tenantId, periodEnd)
                                .map(fx -> renderSchemeWorkbook(rows, periodStart, periodEnd,
                                        reportingCurrency, fx));
                    });
        });
    }

    public Mono<byte[]> groupReportExcel(LocalDate periodStart, LocalDate periodEnd,
                                          String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseTenantId(tenantIdStr);
            return billingReportService.perGroupSummary(periodStart, periodEnd)
                    .flatMap(rows -> {
                        if (rows.size() > ROW_CEILING) {
                            return Mono.error(new IllegalArgumentException(
                                    "Row count " + rows.size() + " exceeds " + ROW_CEILING
                                            + " — narrow the period or filter."));
                        }
                        return loadFxRates(rows, GroupBillingSummaryRow::currencyCode,
                                reportingCurrency, tenantId, periodEnd)
                                .map(fx -> renderGroupWorkbook(rows, periodStart, periodEnd,
                                        reportingCurrency, fx));
                    });
        });
    }

    public Mono<byte[]> memberReportExcel(LocalDate periodStart, LocalDate periodEnd,
                                          String search, String insuranceLine, UUID schemeId,
                                          String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            String tenantIdStr = TenantContext.get(ctx);
            UUID tenantId = parseTenantId(tenantIdStr);
            return billingReportService.perMemberSummary(periodStart, periodEnd,
                            search, insuranceLine, schemeId, 0, ROW_CEILING)
                    .flatMap(page -> {
                        if (page.total() > ROW_CEILING) {
                            return Mono.error(new IllegalArgumentException(
                                    "Row count " + page.total() + " exceeds " + ROW_CEILING
                                            + " — refine filters (search / insurance line / scheme)."));
                        }
                        return loadFxRates(page.content(), MemberBillingSummaryRow::currencyCode,
                                reportingCurrency, tenantId, periodEnd)
                                .map(fx -> renderMemberWorkbook(page.content(), periodStart, periodEnd,
                                        reportingCurrency, fx));
                    });
        });
    }

    // ── Workbook rendering ─────────────────────────────────────────────────

    private byte[] renderSchemeWorkbook(List<SchemeBillingSummaryRow> rows,
                                        LocalDate periodStart, LocalDate periodEnd,
                                        String reportingCurrency, Map<String, BigDecimal> fx) {
        boolean converted = reportingCurrency != null && !reportingCurrency.isBlank();
        ReportWorkbook.SheetWriter sheet = ReportWorkbook.newBook()
                .sheet("Billing by scheme")
                .titleMerged("Billing report — per scheme", converted ? 13 : 12)
                .meta("Period start", periodStart != null ? periodStart.toString() : "—")
                .meta("Period end",   periodEnd   != null ? periodEnd.toString()   : "—")
                .meta("Reporting currency", converted ? reportingCurrency : "(native)")
                .meta("Rows", String.valueOf(rows.size()))
                .blankRow();

        if (converted) {
            sheet.header("Scheme", "Line", "Currency",
                    "Principals", "Dependants", "Lives",
                    "Age 0-18", "Age 19-35", "Age 36-55", "Age 56+",
                    "Total billed", "Total paid",
                    "Total billed (" + reportingCurrency + ")");
        } else {
            sheet.header("Scheme", "Line", "Currency",
                    "Principals", "Dependants", "Lives",
                    "Age 0-18", "Age 19-35", "Age 36-55", "Age 56+",
                    "Total billed", "Total paid");
        }

        sheet.forEach(rows, (sw, row) -> {
            sw.text(row.schemeName())
                    .text(row.insuranceLine())
                    .text(row.currencyCode())
                    .number(row.principalCount())
                    .number(row.dependantCount())
                    .number(row.livesCovered())
                    .money(row.ageBand0_18())
                    .money(row.ageBand19_35())
                    .money(row.ageBand36_55())
                    .money(row.ageBand56Plus())
                    .moneyBold(row.totalBilled())
                    .money(row.totalPaid());
            if (converted) {
                sw.money(convert(row.totalBilled(), row.currencyCode(), fx));
            }
        });

        return sheet.freezeAtHeader().autoSize().toBytes();
    }

    private byte[] renderGroupWorkbook(List<GroupBillingSummaryRow> rows,
                                       LocalDate periodStart, LocalDate periodEnd,
                                       String reportingCurrency, Map<String, BigDecimal> fx) {
        boolean converted = reportingCurrency != null && !reportingCurrency.isBlank();
        ReportWorkbook.SheetWriter sheet = ReportWorkbook.newBook()
                .sheet("Billing by group")
                .titleMerged("Billing report — per group", converted ? 8 : 7)
                .meta("Period start", periodStart != null ? periodStart.toString() : "—")
                .meta("Period end",   periodEnd   != null ? periodEnd.toString()   : "—")
                .meta("Reporting currency", converted ? reportingCurrency : "(native)")
                .meta("Rows", String.valueOf(rows.size()))
                .blankRow();

        if (converted) {
            sheet.header("Group", "Currency",
                    "Principals", "Dependants", "Lives",
                    "Total billed", "Total paid",
                    "Total billed (" + reportingCurrency + ")");
        } else {
            sheet.header("Group", "Currency",
                    "Principals", "Dependants", "Lives",
                    "Total billed", "Total paid");
        }

        sheet.forEach(rows, (sw, row) -> {
            sw.text(row.groupName())
                    .text(row.currencyCode())
                    .number(row.principalCount())
                    .number(row.dependantCount())
                    .number(row.livesCovered())
                    .moneyBold(row.totalBilled())
                    .money(row.totalPaid());
            if (converted) {
                sw.money(convert(row.totalBilled(), row.currencyCode(), fx));
            }
        });

        return sheet.freezeAtHeader().autoSize().toBytes();
    }

    private byte[] renderMemberWorkbook(List<MemberBillingSummaryRow> rows,
                                        LocalDate periodStart, LocalDate periodEnd,
                                        String reportingCurrency, Map<String, BigDecimal> fx) {
        boolean converted = reportingCurrency != null && !reportingCurrency.isBlank();
        int span = converted ? 9 : 8;
        ReportWorkbook.SheetWriter sheet = ReportWorkbook.newBook()
                .sheet("Billing by member")
                .titleMerged("Billing report — per member", span)
                .meta("Period start", periodStart != null ? periodStart.toString() : "—")
                .meta("Period end",   periodEnd   != null ? periodEnd.toString()   : "—")
                .meta("Reporting currency", converted ? reportingCurrency : "(native)")
                .meta("Rows", String.valueOf(rows.size()))
                .blankRow();

        if (converted) {
            sheet.header("Member #", "Member", "Line", "Scheme", "Currency",
                    "Contributions", "Total billed", "Total paid",
                    "Total billed (" + reportingCurrency + ")");
        } else {
            sheet.header("Member #", "Member", "Line", "Scheme", "Currency",
                    "Contributions", "Total billed", "Total paid");
        }

        sheet.forEach(rows, (sw, row) -> {
            sw.text(row.memberNumber())
                    .text(row.memberName())
                    .text(row.insuranceLine())
                    .text(row.schemeName())
                    .text(row.currencyCode())
                    .number(row.contributionCount())
                    .moneyBold(row.totalBilled())
                    .money(row.totalPaid());
            if (converted) sw.money(convert(row.totalBilled(), row.currencyCode(), fx));
        });

        return sheet.freezeAtHeader().autoSize().toBytes();
    }

    // ── FX helpers ─────────────────────────────────────────────────────────

    private <R> Mono<Map<String, BigDecimal>> loadFxRates(List<R> rows,
                                                          java.util.function.Function<R, String> currencyExtractor,
                                                          String reportingCurrency,
                                                          UUID tenantId,
                                                          LocalDate asOf) {
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

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try { return UUID.fromString(tenantIdStr); } catch (IllegalArgumentException e) { return null; }
    }
}
