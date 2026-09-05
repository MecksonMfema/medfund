package com.medfund.claims.siu.service;

import com.medfund.claims.siu.dto.AiCalibrationData;
import com.medfund.claims.siu.dto.FraudReportData;
import com.medfund.claims.siu.dto.InvestigatorProductivityRow;
import com.medfund.claims.siu.dto.MemberTopNRow;
import com.medfund.claims.siu.dto.ProviderTopNRow;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.ReportWorkbook;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Renders the six-sheet XLSX for the fraud / SIU report. §B Phase 11
 * widens the MVP 2-sheet workbook to 6 sheets and gates the two sensitive
 * sheets (AI calibration, Investigator productivity) on
 * {@link WorkbookOptions#includeSensitiveSheets}.
 *
 * <ol>
 *   <li><b>Summary</b> — 6 KPI rows + per-currency savings breakdown.</li>
 *   <li><b>Cases detail</b> — every case opened in the window.</li>
 *   <li><b>Provider top-N</b> — providers ranked by composite savings.</li>
 *   <li><b>Member top-N</b> — members ranked by composite savings.</li>
 *   <li><b>AI calibration</b> — precision per risk_level (sensitive).</li>
 *   <li><b>Investigator productivity</b> — role-filtered (sensitive).</li>
 * </ol>
 *
 * <p>Manual XLSX exports from the Angular page pass {@code includeSensitiveSheets = true}
 * because permission gating already restricts who can hit the endpoint.
 * Phase 12's scheduled adapter passes whatever the schedule row's
 * {@code sensitive_sheets_included} flag is (default false).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FraudReportWorkbookService {

    private static final int ROW_CEILING = 10_000;

    private final DatabaseClient db;
    private final FraudReportService reportService;

    /**
     * Options for {@link #render(ReportResponse, WorkbookOptions, Jwt)}.
     * When {@code includeSensitiveSheets = false} the AI calibration and
     * Investigator productivity sheets are omitted from the workbook.
     */
    public record WorkbookOptions(boolean includeSensitiveSheets) {
        public static WorkbookOptions defaults() {
            return new WorkbookOptions(false);
        }
    }

    /** Back-compat shim for callers that don't need the sensitive sheets. */
    public Mono<byte[]> render(ReportResponse<FraudReportData> env) {
        return render(env, WorkbookOptions.defaults(), null);
    }

    public Mono<byte[]> render(ReportResponse<FraudReportData> env,
                                WorkbookOptions options, Jwt jwt) {
        LocalDate start = env.period() != null ? env.period().periodStart() : null;
        LocalDate end   = env.period() != null ? env.period().periodEnd()   : null;
        String periodStartStr = start != null ? start.toString() : null;
        String periodEndStr   = end != null ? end.toString() : null;

        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            log.debug("Rendering FRAUD_SIU_REPORT XLSX tenant={} period={}..{} sensitive={}",
                    tenantId, start, end, options.includeSensitiveSheets());

            Mono<List<CaseRow>> cases   = loadCases(start, end);
            Mono<List<ProviderTopNRow>> providers = periodStartStr == null
                    ? Mono.just(List.of())
                    : reportService.topProviders(20, periodStartStr, periodEndStr, null);
            Mono<List<MemberTopNRow>>   members   = periodStartStr == null
                    ? Mono.just(List.of())
                    : reportService.topMembers(20, periodStartStr, periodEndStr, null);
            Mono<AiCalibrationData>     calibration = options.includeSensitiveSheets() && periodStartStr != null
                    ? reportService.aiCalibration(periodStartStr, periodEndStr)
                    : Mono.just(new AiCalibrationData(List.of(), List.of()));
            Mono<List<InvestigatorProductivityRow>> productivity =
                    options.includeSensitiveSheets() && periodStartStr != null && jwt != null
                            ? reportService.investigatorProductivity(periodStartStr, periodEndStr, jwt)
                            : Mono.just(List.of());

            return Mono.zip(cases, providers, members, calibration, productivity)
                    .map(t -> build(env, t.getT1(), t.getT2(), t.getT3(),
                            t.getT4(), t.getT5(), options));
        });
    }

    private Mono<List<CaseRow>> loadCases(LocalDate start, LocalDate end) {
        if (start == null || end == null) return Mono.just(List.of());
        return db.sql("""
                        SELECT case_number, status, priority, opened_at, closed_at,
                               saved_amount, saved_currency, closed_by_email, closure_reason
                        FROM siu_case
                        WHERE opened_at >= :start AND opened_at < :end
                        ORDER BY opened_at DESC
                        LIMIT :cap
                        """)
                .bind("start", start)
                .bind("end",   end.plusDays(1))
                .bind("cap",   ROW_CEILING + 1)
                .map((row, meta) -> new CaseRow(
                        row.get("case_number", String.class),
                        row.get("status", String.class),
                        row.get("priority", String.class),
                        row.get("opened_at", OffsetDateTime.class),
                        row.get("closed_at", OffsetDateTime.class),
                        row.get("saved_amount", BigDecimal.class),
                        row.get("saved_currency", String.class),
                        row.get("closed_by_email", String.class),
                        row.get("closure_reason", String.class)))
                .all()
                .collectList();
    }

    private byte[] build(ReportResponse<FraudReportData> env, List<CaseRow> cases,
                          List<ProviderTopNRow> providers, List<MemberTopNRow> members,
                          AiCalibrationData calibration,
                          List<InvestigatorProductivityRow> productivity,
                          WorkbookOptions options) {
        FraudReportData data = env.data();
        String currency = env.reportingCurrency() != null ? env.reportingCurrency() : "";

        // Sheet 1 — Summary (six tiles + per-currency)
        ReportWorkbook.SheetWriter summary = ReportWorkbook.newBook()
                .sheet("Summary")
                .titleMerged("Fraud / SIU report", 4)
                .meta("Period start", env.period() != null && env.period().periodStart() != null
                        ? env.period().periodStart().toString() : "—")
                .meta("Period end",   env.period() != null && env.period().periodEnd() != null
                        ? env.period().periodEnd().toString() : "—")
                .meta("Reporting currency", currency)
                .meta("Generated at", env.generatedAt() != null
                        ? env.generatedAt().toString() : "—")
                .meta("Sensitive sheets", options.includeSensitiveSheets() ? "included" : "omitted")
                .blankRow()
                .header("Metric", "Value", "", "")
                .text("Cases opened").number(data.casesOpened()).text("").text("").nextRow()
                .text("Confirmed count").number(data.confirmedCount()).text("").text("").nextRow()
                .text("Savings (composite, " + currency + ")").money(data.savingsComposite())
                        .text("").text("").nextRow()
                .text("Confirmation rate").money(data.confirmationRate())
                        .text("").text("").nextRow()
                .text("Avg cycle time (days)").money(data.avgCycleTimeDays())
                        .text("").text("").nextRow()
                .text("Reopened count").number(data.reopenedCount()).text("").text("").nextRow()
                .blankRow()
                .header("Savings per currency", "Currency", "Amount", "");
        for (var e : data.savingsPerCurrency().entrySet()) {
            summary.text("").text(e.getKey()).money(e.getValue()).text("").nextRow();
        }
        var book = summary.freezeAtHeader().autoSize().end();

        // Sheet 2 — Cases detail
        ReportWorkbook.SheetWriter detail = book.sheet("Cases detail")
                .titleMerged("SIU cases opened in window", 9)
                .meta("Row count", String.valueOf(Math.min(cases.size(), ROW_CEILING)))
                .blankRow()
                .header("Case number", "Status", "Priority",
                        "Opened at", "Closed at",
                        "Saved amount", "Saved currency",
                        "Closed by", "Closure reason");
        int cappedCases = Math.min(cases.size(), ROW_CEILING);
        for (int i = 0; i < cappedCases; i++) {
            CaseRow r = cases.get(i);
            detail.text(nvl(r.caseNumber))
                    .text(nvl(r.status))
                    .text(nvl(r.priority))
                    .text(r.openedAt != null ? r.openedAt.toString() : "")
                    .text(r.closedAt != null ? r.closedAt.toString() : "")
                    .money(r.savedAmount)
                    .text(nvl(r.savedCurrency))
                    .text(nvl(r.closedByEmail))
                    .text(nvl(r.closureReason))
                    .nextRow();
        }
        book = detail.freezeAtHeader().autoSize().end();

        // Sheet 3 — Provider top-N
        ReportWorkbook.SheetWriter provSheet = book.sheet("Provider top-N")
                .titleMerged("Top providers by composite confirmed savings", 5)
                .meta("Row count", String.valueOf(providers.size()))
                .blankRow()
                .header("Provider id", "Confirmed cases",
                        "Savings composite (" + currency + ")",
                        "Native currencies", "");
        for (ProviderTopNRow r : providers) {
            provSheet.text(r.providerId() != null ? r.providerId().toString() : "")
                    .number(r.confirmedCases())
                    .money(r.savingsComposite())
                    .text(nativeSummary(r.savingsNative()))
                    .text("")
                    .nextRow();
        }
        book = provSheet.freezeAtHeader().autoSize().end();

        // Sheet 4 — Member top-N
        ReportWorkbook.SheetWriter memSheet = book.sheet("Member top-N")
                .titleMerged("Top members by composite confirmed savings", 5)
                .meta("Row count", String.valueOf(members.size()))
                .blankRow()
                .header("Member id", "Confirmed cases",
                        "Savings composite (" + currency + ")",
                        "Native currencies", "");
        for (MemberTopNRow r : members) {
            memSheet.text(r.memberId() != null ? r.memberId().toString() : "")
                    .number(r.confirmedCases())
                    .money(r.savingsComposite())
                    .text(nativeSummary(r.savingsNative()))
                    .text("")
                    .nextRow();
        }
        book = memSheet.freezeAtHeader().autoSize().end();

        if (options.includeSensitiveSheets()) {
            // Sheet 5 — AI calibration
            ReportWorkbook.SheetWriter cal = book.sheet("AI calibration")
                    .titleMerged("AI calibration by risk level", 5)
                    .meta("Row count", String.valueOf(calibration.rows().size()));
            for (String w : calibration.warnings()) {
                cal.meta("Warning", w);
            }
            cal.blankRow()
                    .header("Risk level", "True positives",
                            "False positives", "Total flags", "Precision (4dp)");
            for (AiCalibrationData.CalibrationRow r : calibration.rows()) {
                cal.text(nvl(r.riskLevel()))
                        .number(r.truePositives())
                        .number(r.falsePositives())
                        .number(r.totalFlags())
                        .text(nvl(r.precision4dp()))
                        .nextRow();
            }
            book = cal.freezeAtHeader().autoSize().end();

            // Sheet 6 — Investigator productivity
            ReportWorkbook.SheetWriter prod = book.sheet("Investigator productivity")
                    .titleMerged("Investigator productivity (role-filtered)", 6)
                    .meta("Row count", String.valueOf(productivity.size()))
                    .blankRow()
                    .header("Officer email", "Closed", "Confirmed",
                            "Dismissed", "Referred / action-taken",
                            "Avg cycle days");
            for (InvestigatorProductivityRow r : productivity) {
                prod.text(nvl(r.officerEmail()))
                        .number(r.casesClosed())
                        .number(r.confirmedCount())
                        .number(r.dismissedCount())
                        .number(r.referredCount())
                        .money(r.avgCycleTimeDays())
                        .nextRow();
            }
            book = prod.freezeAtHeader().autoSize().end();
        }

        return book.toBytes();
    }

    private static String nvl(String s) {
        return s != null ? s : "";
    }

    /** Render a native-savings map as an inline compact string
     *  ("USD 1000.00 · ZWL 4500.00") for the XLSX row. */
    private static String nativeSummary(java.util.Map<String, BigDecimal> perCurrency) {
        if (perCurrency == null || perCurrency.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (var e : perCurrency.entrySet()) {
            if (!first) sb.append(" · ");
            sb.append(e.getKey()).append(' ').append(e.getValue().toPlainString());
            first = false;
        }
        return sb.toString();
    }

    private record CaseRow(
            String caseNumber,
            String status,
            String priority,
            OffsetDateTime openedAt,
            OffsetDateTime closedAt,
            BigDecimal savedAmount,
            String savedCurrency,
            String closedByEmail,
            String closureReason
    ) {
    }
}
