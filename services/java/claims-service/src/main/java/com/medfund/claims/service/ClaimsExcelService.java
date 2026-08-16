package com.medfund.claims.service;

import com.medfund.claims.dto.ClaimStatusMatrixCell;
import com.medfund.claims.dto.ClaimStatusMatrixResponse;
import com.medfund.claims.dto.ClaimsDetailResponse;
import com.medfund.claims.dto.ClaimsSummaryRow;
import com.medfund.claims.dto.DenialAnalysisResponse;
import com.medfund.claims.dto.FrequencySeverityRow;
import com.medfund.claims.dto.HighCostClaimantRow;
import com.medfund.claims.dto.PageResponse;
import com.medfund.claims.dto.PreAuthActivityResponse;
import com.medfund.claims.dto.PreAuthActivityRow;
import com.medfund.shared.report.FxRateReader;
import com.medfund.shared.report.ReportWorkbook;
import com.medfund.shared.report.ReportingCurrencyResolver;
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
import java.util.function.Function;

/**
 * XLSX generator for the claims-financial report family (Phase 4 §A).
 * Rows stay native-currency per G25; when a reporting currency is passed
 * the workbook adds a rightmost "Amount in {reportingCurrency}" column
 * populated via {@link FxRateReader#findRate} (missing FX skips that cell
 * rather than failing the export).
 *
 * <p>Detail exports use two sheets (monthly buckets + claim ledger) per
 * G40; the ledger sheet is capped at 10k rows — beyond that the export
 * refuses with "refine filters" (plan §10).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClaimsExcelService {

    private static final int ROW_CEILING = 10_000;

    private final ClaimsReportService claimsReportService;
    private final HighCostClaimantService highCostClaimantService;
    private final PreAuthActivityService preAuthActivityService;
    private final FxRateReader fxRateReader;
    private final ReportingCurrencyResolver currencyResolver;

    // ── Summary exports ────────────────────────────────────────────────────

    public Mono<byte[]> schemesReportExcel(LocalDate periodStart, LocalDate periodEnd,
                                           String reportingCurrency, String insuranceLine) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return claimsReportService.perSchemeSummary(periodStart, periodEnd, insuranceLine)
                    .flatMap(rows -> renderSummaryWorkbook(rows,
                            "Claims by scheme", "Scheme", false,
                            periodStart, periodEnd, reportingCurrency, tenantId));
        });
    }

    public Mono<byte[]> providersReportExcel(LocalDate periodStart, LocalDate periodEnd,
                                             String reportingCurrency, String insuranceLine) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return claimsReportService.perProviderSummary(periodStart, periodEnd, insuranceLine)
                    .flatMap(rows -> renderSummaryWorkbook(rows,
                            "Claims by provider", "Provider", false,
                            periodStart, periodEnd, reportingCurrency, tenantId));
        });
    }

    public Mono<byte[]> groupsReportExcel(LocalDate periodStart, LocalDate periodEnd,
                                          String reportingCurrency, String insuranceLine) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return claimsReportService.perGroupSummary(periodStart, periodEnd, insuranceLine)
                    .flatMap(rows -> renderSummaryWorkbook(rows,
                            "Claims by group", "Group", false,
                            periodStart, periodEnd, reportingCurrency, tenantId));
        });
    }

    public Mono<byte[]> membersReportExcel(LocalDate periodStart, LocalDate periodEnd,
                                           String search, String insuranceLine,
                                           UUID schemeId, UUID providerId, String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            // Cap at ROW_CEILING — exports never paginate (plan §11).
            return claimsReportService.perMemberSummary(periodStart, periodEnd,
                            search, insuranceLine, schemeId, providerId, 0, ROW_CEILING)
                    .flatMap(page -> renderSummaryWorkbook(page.content(),
                            "Claims by member", "Member", true,
                            periodStart, periodEnd, reportingCurrency, tenantId));
        });
    }

    public Mono<byte[]> highCostClaimantsExcel(LocalDate periodStart, LocalDate periodEnd,
                                               String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return currencyResolver.resolve(tenantId, reportingCurrency)
                    .flatMap(rc -> highCostClaimantService.report(periodStart, periodEnd, rc, tenantId))
                    .flatMap(result -> renderHighCostWorkbook(result.rows(),
                            periodStart, periodEnd, reportingCurrency, tenantId));
        });
    }

    // ── Detail export (drill-down) ─────────────────────────────────────────

    public Mono<byte[]> detailExcel(String dimension, UUID id, String dimensionLabel,
                                    LocalDate periodStart, LocalDate periodEnd,
                                    String status, UUID providerId, String currencyCode,
                                    String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            // Cap at ROW_CEILING — one page grab, exports never paginate.
            return claimsReportService.detail(dimension, id, periodStart, periodEnd,
                            status, providerId, currencyCode, 0, ROW_CEILING)
                    .flatMap(detail -> renderDetailWorkbook(detail, dimensionLabel,
                            periodStart, periodEnd, reportingCurrency, tenantId));
        });
    }

    // ── Pre-auth export ────────────────────────────────────────────────────

    public Mono<byte[]> preAuthActivityExcel(LocalDate periodStart, LocalDate periodEnd,
                                             String reportingCurrency, String status, UUID providerId) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return preAuthActivityService.activity(periodStart, periodEnd, status, providerId)
                    .flatMap(payload -> renderPreAuthWorkbook(payload, reportingCurrency, tenantId));
        });
    }

    // ── §B exports ─────────────────────────────────────────────────────────

    /**
     * CLAIM_STATUS_LIST export — sheet 1 the matrix counts grid, sheet 2 the
     * full-window drill ledger (null status / ageBucket → whole window), so
     * the workbook is exactly the report the user is looking at. The ledger
     * sheet refuses when the window exceeds ROW_CEILING rows.
     */
    public Mono<byte[]> statusMatrixExcel(LocalDate submittedFrom, LocalDate submittedTo,
                                          String reportingCurrency, String insuranceLine) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return claimsReportService.statusMatrix(submittedFrom, submittedTo, insuranceLine)
                    .zipWith(claimsReportService.statusMatrixDrill(submittedFrom, submittedTo,
                            null, null, 0, ROW_CEILING))
                    .flatMap(t -> renderStatusMatrixWorkbook(t.getT1(), t.getT2(),
                            submittedFrom, submittedTo, reportingCurrency, tenantId));
        });
    }

    public Mono<byte[]> denialAnalysisExcel(LocalDate periodStart, LocalDate periodEnd,
                                            String category, String code, UUID providerId,
                                            String reportingCurrency) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return claimsReportService.denialAnalysis(periodStart, periodEnd, category, code, providerId)
                    .flatMap(payload -> renderDenialWorkbook(payload, reportingCurrency, tenantId));
        });
    }

    public Mono<byte[]> frequencySeverityExcel(LocalDate serviceFrom, LocalDate serviceTo,
                                               String reportingCurrency, String insuranceLine) {
        return Mono.deferContextual(ctx -> {
            UUID tenantId = parseTenantId(TenantContext.get(ctx));
            return claimsReportService.frequencySeverity(serviceFrom, serviceTo, insuranceLine)
                    .flatMap(result -> renderFrequencySeverityWorkbook(result.rows(),
                            serviceFrom, serviceTo, reportingCurrency, tenantId));
        });
    }

    // ── Workbook rendering ─────────────────────────────────────────────────

    private Mono<byte[]> renderSummaryWorkbook(List<ClaimsSummaryRow> rows,
                                                String sheetName, String dimensionLabel, boolean showLine,
                                                LocalDate periodStart, LocalDate periodEnd,
                                                String reportingCurrency, UUID tenantId) {
        if (rows.size() > ROW_CEILING) {
            return Mono.error(new IllegalArgumentException(
                    "Row count " + rows.size() + " exceeds " + ROW_CEILING
                            + " — narrow the period or filter."));
        }
        return loadFxRates(rows, ClaimsSummaryRow::currencyCode, reportingCurrency, tenantId, periodEnd)
                .map(fx -> {
                    boolean converted = reportingCurrency != null && !reportingCurrency.isBlank();
                    int span = (showLine ? 1 : 0) + (converted ? 8 : 7);

                    ReportWorkbook.SheetWriter sheet = ReportWorkbook.newBook()
                            .sheet(sheetName)
                            .titleMerged("Claims report — per " + dimensionLabel.toLowerCase(), span)
                            .meta("Period start", periodStart != null ? periodStart.toString() : "—")
                            .meta("Period end",   periodEnd   != null ? periodEnd.toString()   : "—")
                            .meta("Reporting currency", converted ? reportingCurrency : "(native)")
                            .meta("Rows", String.valueOf(rows.size()))
                            .blankRow();

                    if (showLine) {
                        if (converted) {
                            sheet.header(dimensionLabel, "Line", "Currency", "Claims",
                                    "Claimed", "Approved", "Paid",
                                    "Amount in " + reportingCurrency);
                        } else {
                            sheet.header(dimensionLabel, "Line", "Currency", "Claims",
                                    "Claimed", "Approved", "Paid");
                        }
                    } else if (converted) {
                        sheet.header(dimensionLabel, "Currency", "Claims",
                                "Claimed", "Approved", "Paid",
                                "Amount in " + reportingCurrency);
                    } else {
                        sheet.header(dimensionLabel, "Currency", "Claims",
                                "Claimed", "Approved", "Paid");
                    }
                    sheet.forEach(rows, (sw, row) -> {
                        sw.text(row.dimensionName());
                        if (showLine) sw.text(row.insuranceLine());
                        sw.text(row.currencyCode())
                                .number(row.claimCount())
                                .money(row.totalClaimed())
                                .money(row.totalApproved())
                                .moneyBold(row.totalPaid());
                        if (converted) sw.money(convert(row.totalPaid(), row.currencyCode(), fx));
                    });
                    return sheet.freezeAtHeader().autoSize().toBytes();
                });
    }

    private Mono<byte[]> renderHighCostWorkbook(List<HighCostClaimantRow> rows,
                                                LocalDate periodStart, LocalDate periodEnd,
                                                String reportingCurrency, UUID tenantId) {
        if (rows.size() > ROW_CEILING) {
            return Mono.error(new IllegalArgumentException(
                    "Row count " + rows.size() + " exceeds " + ROW_CEILING
                            + " — narrow the period."));
        }
        return loadFxRates(rows, HighCostClaimantRow::currencyCode, reportingCurrency, tenantId, periodEnd)
                .map(fx -> {
                    boolean converted = reportingCurrency != null && !reportingCurrency.isBlank();
                    int span = converted ? 7 : 6;

                    ReportWorkbook.SheetWriter sheet = ReportWorkbook.newBook()
                            .sheet("High-cost claimants")
                            .titleMerged("High-cost claimants", span)
                            .meta("Period start", periodStart != null ? periodStart.toString() : "—")
                            .meta("Period end",   periodEnd   != null ? periodEnd.toString()   : "—")
                            .meta("Reporting currency", converted ? reportingCurrency : "(native)")
                            .meta("Rows", String.valueOf(rows.size()))
                            .blankRow();

                    if (converted) {
                        sheet.header("Member number", "Member", "Currency",
                                "Cumulative paid", "Claims",
                                "Cumulative paid (" + reportingCurrency + ")");
                    } else {
                        sheet.header("Member number", "Member", "Currency",
                                "Cumulative paid", "Claims");
                    }
                    sheet.forEach(rows, (sw, row) -> {
                        sw.text(row.memberNumber())
                                .text(row.memberName())
                                .text(row.currencyCode())
                                .moneyBold(row.cumulativePaid())
                                .number(row.contributingClaims());
                        if (converted) sw.money(convert(row.cumulativePaid(), row.currencyCode(), fx));
                    });
                    return sheet.freezeAtHeader().autoSize().toBytes();
                });
    }

    private Mono<byte[]> renderDetailWorkbook(ClaimsDetailResponse detail, String dimensionLabel,
                                               LocalDate periodStart, LocalDate periodEnd,
                                               String reportingCurrency, UUID tenantId) {
        com.medfund.claims.dto.PageResponse<ClaimsDetailResponse.ClaimLedgerRow> ledger = detail.claims();
        if (ledger != null && ledger.total() > ROW_CEILING) {
            return Mono.error(new IllegalArgumentException(
                    "Ledger row count " + ledger.total() + " exceeds " + ROW_CEILING
                            + " — refine filters (status / provider / currency)."));
        }

        List<ClaimsDetailResponse.MonthlyBucket> buckets =
                detail.monthlyBuckets() != null ? detail.monthlyBuckets() : List.of();
        List<ClaimsDetailResponse.ClaimLedgerRow> rows =
                ledger != null && ledger.content() != null ? ledger.content() : List.of();

        return loadFxRates(rows, ClaimsDetailResponse.ClaimLedgerRow::currencyCode,
                        reportingCurrency, tenantId, periodEnd)
                .map(fx -> {
                    boolean converted = reportingCurrency != null && !reportingCurrency.isBlank();

                    ReportWorkbook book = ReportWorkbook.newBook();

                    ReportWorkbook.SheetWriter monthlySheet = book.sheet("Monthly buckets")
                            .titleMerged("Claims by month — " + safeName(detail.dimensionName()), 6)
                            .meta("Dimension", dimensionLabel)
                            .meta("Period start", periodStart != null ? periodStart.toString() : "—")
                            .meta("Period end",   periodEnd   != null ? periodEnd.toString()   : "—")
                            .blankRow()
                            .header("Month", "Currency", "Claims",
                                    "Claimed", "Approved", "Paid");
                    monthlySheet.forEach(buckets, (sw, b) -> sw
                            .date(b.month())
                            .text(b.currencyCode())
                            .number(b.claimCount())
                            .money(b.totalClaimed())
                            .money(b.totalApproved())
                            .moneyBold(b.totalPaid()));
                    monthlySheet.freezeAtHeader().autoSize();

                    int span = converted ? 14 : 13;
                    ReportWorkbook.SheetWriter ledgerSheet = book.sheet("Claim ledger")
                            .titleMerged("Claim ledger — " + safeName(detail.dimensionName()), span)
                            .meta("Rows", String.valueOf(rows.size()))
                            .meta("Reporting currency", converted ? reportingCurrency : "(native)")
                            .blankRow();

                    if (converted) {
                        ledgerSheet.header("Claim number", "Member", "Provider",
                                "Submitted", "Service date", "Adjudicated",
                                "Status", "Rejection", "Claimed", "Approved", "Paid",
                                "Currency", "Paid (" + reportingCurrency + ")");
                    } else {
                        ledgerSheet.header("Claim number", "Member", "Provider",
                                "Submitted", "Service date", "Adjudicated",
                                "Status", "Rejection", "Claimed", "Approved", "Paid",
                                "Currency");
                    }

                    ledgerSheet.forEach(rows, (sw, row) -> {
                        sw.text(row.claimNumber())
                                .text(row.memberName())
                                .text(row.providerName())
                                .date(row.submissionDate())
                                .date(row.serviceDate())
                                .date(row.adjudicatedAt())
                                .text(row.status())
                                .text(row.rejectionCode())
                                .money(row.claimedAmount())
                                .money(row.approvedAmount())
                                .moneyBold(row.paidAmount())
                                .text(row.currencyCode());
                        if (converted) sw.money(convert(row.paidAmount(), row.currencyCode(), fx));
                    });

                    return ledgerSheet.freezeAtHeader().autoSize().toBytes();
                });
    }

    private Mono<byte[]> renderPreAuthWorkbook(PreAuthActivityResponse payload, String reportingCurrency,
                                               UUID tenantId) {
        List<PreAuthActivityRow> rows =
                payload.byStatus() != null ? payload.byStatus() : List.of();
        PreAuthActivityResponse.R04R05SignalRow signal = payload.r04r05Signal();

        return loadFxRates(rows, PreAuthActivityRow::currencyCode, reportingCurrency, tenantId, LocalDate.now())
                .map(fx -> {
                    boolean converted = reportingCurrency != null && !reportingCurrency.isBlank();
                    int span = converted ? 8 : 7;

                    ReportWorkbook.SheetWriter sheet = ReportWorkbook.newBook()
                            .sheet("Pre-auth activity")
                            .titleMerged("Pre-auth activity", span)
                            .meta("Reporting currency", converted ? reportingCurrency : "(native)")
                            .blankRow();

                    if (converted) {
                        sheet.header("Status", "Currency", "Count",
                                "Requested", "Approved", "Avg decision days",
                                "Approval %", "Expiry %",
                                "Requested (" + reportingCurrency + ")");
                    } else {
                        sheet.header("Status", "Currency", "Count",
                                "Requested", "Approved", "Avg decision days",
                                "Approval %", "Expiry %");
                    }
                    sheet.forEach(rows, (sw, row) -> {
                        sw.text(row.status())
                                .text(row.currencyCode())
                                .number(row.count())
                                .money(row.totalRequested())
                                .money(row.totalApproved())
                                .text(pct(row.avgDecisionDays()))
                                .text(pct(row.approvalRatePct()))
                                .text(pct(row.expiryRatePct()));
                        if (converted) sw.money(convert(row.totalRequested(), row.currencyCode(), fx));
                    });

                    if (signal != null) {
                        sheet.blankRow()
                                .meta("R04 signal (required but not provided)",
                                        signal.r04Count() + " claims / "
                                                + signal.totalClaimedInR04R05().toPlainString() + " claimed")
                                .meta("R05 signal (pre-auth expired)",
                                        String.valueOf(signal.r05Count()) + " claims");
                    }

                    return sheet.freezeAtHeader().autoSize().toBytes();
                });
    }

    private Mono<byte[]> renderStatusMatrixWorkbook(ClaimStatusMatrixResponse matrix,
                                                    PageResponse<ClaimsDetailResponse.ClaimLedgerRow> drill,
                                                    LocalDate submittedFrom, LocalDate submittedTo,
                                                    String reportingCurrency, UUID tenantId) {
        if (drill != null && drill.total() > ROW_CEILING) {
            return Mono.error(new IllegalArgumentException(
                    "Ledger row count " + drill.total() + " exceeds " + ROW_CEILING
                            + " — narrow the submission window."));
        }
        List<ClaimStatusMatrixCell> cells = matrix.cells() != null ? matrix.cells() : List.of();
        List<ClaimsDetailResponse.ClaimLedgerRow> rows =
                drill != null && drill.content() != null ? drill.content() : List.of();

        return loadFxRates(rows, ClaimsDetailResponse.ClaimLedgerRow::currencyCode,
                        reportingCurrency, tenantId, submittedTo)
                .map(fx -> {
                    boolean converted = reportingCurrency != null && !reportingCurrency.isBlank();

                    ReportWorkbook book = ReportWorkbook.newBook();

                    ReportWorkbook.SheetWriter matrixSheet = book.sheet("Status matrix")
                            .titleMerged("Claim status matrix", 7)
                            .meta("Submitted from", submittedFrom != null ? submittedFrom.toString() : "—")
                            .meta("Submitted to",   submittedTo   != null ? submittedTo.toString()   : "—")
                            .meta("As of", matrix.asOf() != null ? matrix.asOf().toString() : "—")
                            .blankRow()
                            .header("Status", "Age bucket", "Currency",
                                    "Claims", "Claimed", "Approved", "Paid");
                    matrixSheet.forEach(cells, (sw, c) -> sw
                            .text(c.status())
                            .text(c.ageBucket())
                            .text(c.currencyCode())
                            .number(c.claimCount())
                            .money(c.totalClaimed())
                            .money(c.totalApproved())
                            .moneyBold(c.totalPaid()));
                    matrixSheet.freezeAtHeader().autoSize();

                    int span = converted ? 14 : 13;
                    ReportWorkbook.SheetWriter ledgerSheet = book.sheet("Claim ledger")
                            .titleMerged("Claim ledger — full submission window", span)
                            .meta("Rows", String.valueOf(rows.size()))
                            .meta("Reporting currency", converted ? reportingCurrency : "(native)")
                            .blankRow();

                    if (converted) {
                        ledgerSheet.header("Claim number", "Member", "Provider",
                                "Submitted", "Service date", "Adjudicated",
                                "Status", "Rejection", "Claimed", "Approved", "Paid",
                                "Currency", "Paid (" + reportingCurrency + ")");
                    } else {
                        ledgerSheet.header("Claim number", "Member", "Provider",
                                "Submitted", "Service date", "Adjudicated",
                                "Status", "Rejection", "Claimed", "Approved", "Paid",
                                "Currency");
                    }

                    ledgerSheet.forEach(rows, (sw, row) -> {
                        sw.text(row.claimNumber())
                                .text(row.memberName())
                                .text(row.providerName())
                                .date(row.submissionDate())
                                .date(row.serviceDate())
                                .date(row.adjudicatedAt())
                                .text(row.status())
                                .text(row.rejectionCode())
                                .money(row.claimedAmount())
                                .money(row.approvedAmount())
                                .moneyBold(row.paidAmount())
                                .text(row.currencyCode());
                        if (converted) sw.money(convert(row.paidAmount(), row.currencyCode(), fx));
                    });

                    return ledgerSheet.freezeAtHeader().autoSize().toBytes();
                });
    }

    private Mono<byte[]> renderDenialWorkbook(DenialAnalysisResponse payload,
                                              String reportingCurrency, UUID tenantId) {
        List<DenialAnalysisResponse.CategoryRow> categories =
                payload.byCategory() != null ? payload.byCategory() : List.of();
        List<DenialAnalysisResponse.CodeRow> codes =
                payload.byCode() != null ? payload.byCode() : List.of();
        List<DenialAnalysisResponse.ProviderRow> providers =
                payload.byProvider() != null ? payload.byProvider() : List.of();

        ReportWorkbook book = ReportWorkbook.newBook();
        book.sheet("Categories")
                .titleMerged("Denial analysis — by category", 3)
                .meta("Reporting currency", (reportingCurrency != null && !reportingCurrency.isBlank())
                        ? reportingCurrency : "(native)")
                .blankRow()
                .header("Category", "Claims", "Claimed")
                .forEach(categories, (sw, r) -> sw
                        .text(r.category())
                        .number(r.claimCount())
                        .money(r.totalClaimed()))
                .freezeAtHeader().autoSize();

        book.sheet("Codes")
                .titleMerged("Denial analysis — by rejection code", 5)
                .blankRow()
                .header("Code", "Category", "Description", "Claims", "Claimed")
                .forEach(codes, (sw, r) -> sw
                        .text(r.code())
                        .text(r.category())
                        .text(r.description())
                        .number(r.claimCount())
                        .money(r.totalClaimed()))
                .freezeAtHeader().autoSize();

        book.sheet("Providers")
                .titleMerged("Denial analysis — by provider", 4)
                .blankRow()
                .header("Provider", "Claims denied", "Claimed (denied)", "Denial rate")
                .forEach(providers, (sw, r) -> sw
                        .text(r.providerName())
                        .number(r.claimCount())
                        .money(r.totalClaimed())
                        .text(pct(r.denialRatePct())))
                .freezeAtHeader().autoSize();

        return Mono.just(book.toBytes());
    }

    private Mono<byte[]> renderFrequencySeverityWorkbook(List<FrequencySeverityRow> rows,
                                                         LocalDate serviceFrom, LocalDate serviceTo,
                                                         String reportingCurrency, UUID tenantId) {
        if (rows.size() > ROW_CEILING) {
            return Mono.error(new IllegalArgumentException(
                    "Row count " + rows.size() + " exceeds " + ROW_CEILING
                            + " — narrow the service-date window."));
        }
        return Mono.just(ReportWorkbook.newBook()
                .sheet("Frequency & severity")
                .titleMerged("Claims frequency & severity", 10)
                .meta("Service from", serviceFrom != null ? serviceFrom.toString() : "—")
                .meta("Service to",   serviceTo   != null ? serviceTo.toString()   : "—")
                .meta("Reporting currency", (reportingCurrency != null && !reportingCurrency.isBlank())
                        ? reportingCurrency : "(native)")
                .meta("Exposure", "Active members × days ÷ 30.4375 (G48 fallback)")
                .blankRow()
                .header("Scheme", "Line", "Currency", "Exposure (member-months)",
                        "Claims", "Frequency (annualised)", "Severity mean",
                        "Severity median", "Severity P95")
                .forEach(rows, (sw, r) -> sw
                        .text(r.schemeName())
                        .text(r.insuranceLine())
                        .text(r.currencyCode())
                        .money(r.exposureMemberMonths())
                        .number(r.claimCount())
                        .money(r.frequency())
                        .money(r.severityMean())
                        .money(r.severityMedian())
                        .money(r.severityP95()))
                .freezeAtHeader().autoSize()
                .toBytes());
    }

    // ── FX helpers ─────────────────────────────────────────────────────────

    private <R> Mono<Map<String, BigDecimal>> loadFxRates(List<R> rows,
                                                          Function<R, String> currencyExtractor,
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

    private static String pct(BigDecimal value) {
        return value != null ? value.toPlainString() + "%" : "—";
    }

    private static String safeName(String name) {
        return name != null && !name.isBlank() ? name : "—";
    }

    private static UUID parseTenantId(String tenantIdStr) {
        if (tenantIdStr == null || tenantIdStr.isBlank()) return null;
        try { return UUID.fromString(tenantIdStr); } catch (IllegalArgumentException e) { return null; }
    }
}
