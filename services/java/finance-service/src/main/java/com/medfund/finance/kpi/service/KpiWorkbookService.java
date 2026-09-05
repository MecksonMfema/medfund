package com.medfund.finance.kpi.service;

import com.medfund.finance.kpi.dto.KpiReportData;
import com.medfund.finance.kpi.dto.KpiRequest;
import com.medfund.finance.kpi.dto.KpiTrendPoint;
import com.medfund.finance.kpi.dto.KpiValue;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.report.ReportWorkbook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Phase 18 §Phase 8 — XLSX renderer for every executive KPI. One workbook per
 * fire: a Summary sheet carrying the composite ratio + numerator/denominator +
 * per-currency breakdown, and a Trend (12 months) sheet carrying the sparkline
 * data in tabular form for board packs. Backs both the on-demand "Export XLSX"
 * button on the KPI dashboard (Phase 7) and the scheduled-email path
 * ({@link com.medfund.finance.report.schedule.adapter}).
 *
 * <p>Delegates to {@link KpiComposerService} — the composer already carries
 * the tenant enablement gate, cache, and peer-failure warnings, so the
 * workbook just serialises whatever envelope + trend the composer returns.
 * Peer-failure warnings surface as a header meta row so the recipient sees
 * "why the numbers might be low" without needing to open a JSON payload.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KpiWorkbookService {

    private static final int SUMMARY_TITLE_SPAN = 4;
    private static final int TREND_WINDOW_MONTHS = 12;

    private final KpiComposerService composer;

    /**
     * Render a full KPI workbook — the composer computes the latest tile and
     * the 12-month trend in parallel; failures on either arm surface as
     * warnings on the summary sheet rather than aborting the render.
     */
    public Mono<byte[]> workbook(ReportKey key, KpiRequest req) {
        return Mono.zip(dispatchSingle(key, req), composer.trend(key, req, TREND_WINDOW_MONTHS))
                .map(t -> render(key, t.getT1(), t.getT2()));
    }

    private Mono<ReportResponse<KpiReportData>> dispatchSingle(ReportKey key, KpiRequest req) {
        return switch (key) {
            case LOSS_RATIO_KPI   -> composer.lossRatio(req);
            case EXPENSE_RATIO    -> composer.expenseRatio(req);
            case COMBINED_RATIO   -> composer.combinedRatio(req);
            case CLAIMS_FREQUENCY -> composer.claimsFrequency(req);
            case AVERAGE_SEVERITY -> composer.averageSeverity(req);
            default -> Mono.error(new IllegalArgumentException(
                    "Not a dashboard KPI key: " + key.name()));
        };
    }

    private byte[] render(ReportKey key, ReportResponse<KpiReportData> latest,
                          List<KpiTrendPoint> trend) {
        ReportWorkbook book = ReportWorkbook.newBook();

        // ── Summary sheet ────────────────────────────────────────────────
        ReportWorkbook.SheetWriter summary = book.sheet("Summary")
                .titleMerged(key.getLabel(), SUMMARY_TITLE_SPAN)
                .meta("Period start", periodStart(latest))
                .meta("Period end",   periodEnd(latest))
                .meta("Reporting currency", latest.reportingCurrency())
                .meta("Composite ratio",    formatRatio(key, latest.data().compositeRatio()))
                .metaMoney("Numerator",     latest.data().compositeNumerator())
                .metaMoney("Denominator",   latest.data().compositeDenominator())
                .meta("Basis", latest.data().basisNote() != null
                        ? latest.data().basisNote()
                        : "Single basis");

        List<String> warnings = latest.warnings();
        if (warnings != null && !warnings.isEmpty()) {
            summary.meta("Warnings", String.valueOf(warnings.size()));
            for (String w : warnings) summary.meta("", w);
        }

        summary.blankRow()
                .header("Currency", "Ratio", "Numerator", "Denominator");

        Map<String, KpiValue> perCurrency = latest.data().perCurrency();
        if (perCurrency != null && !perCurrency.isEmpty()) {
            summary.forEach(perCurrency.entrySet(), (sw, entry) -> {
                KpiValue v = entry.getValue();
                sw.text(entry.getKey())
                        .text(formatRatio(key, v.ratio()))
                        .moneyBold(v.numerator())
                        .moneyBold(v.denominator());
            });
        }

        summary.freezeAtHeader().autoSize();

        // ── Trend sheet ──────────────────────────────────────────────────
        ReportWorkbook.SheetWriter trendSheet = book.sheet("Trend (12 months)")
                .titleMerged(key.getLabel() + " — 12-month trend", SUMMARY_TITLE_SPAN)
                .header("Period start", "Period end", "Composite ratio", "Warnings");

        if (trend != null && !trend.isEmpty()) {
            trendSheet.forEach(trend, (sw, pt) -> sw
                    .text(pt.periodStart() != null ? pt.periodStart().toString() : "")
                    .text(pt.periodEnd()   != null ? pt.periodEnd().toString()   : "")
                    .text(formatRatio(key, pt.composite() != null
                            ? pt.composite().compositeRatio() : null))
                    .text(pt.warnings() != null && !pt.warnings().isEmpty()
                            ? String.join("; ", pt.warnings())
                            : ""));
        }

        trendSheet.freezeAtHeader().autoSize();

        return book.toBytes();
    }

    private static String periodStart(ReportResponse<KpiReportData> env) {
        return env.period() != null && env.period().periodStart() != null
                ? env.period().periodStart().toString()
                : "—";
    }

    private static String periodEnd(ReportResponse<KpiReportData> env) {
        return env.period() != null && env.period().periodEnd() != null
                ? env.period().periodEnd().toString()
                : "—";
    }

    /**
     * Format a KPI ratio for a spreadsheet cell. LOSS_RATIO_KPI, EXPENSE_RATIO,
     * COMBINED_RATIO and CLAIMS_FREQUENCY are proportions rendered as
     * percentages with 1 decimal; AVERAGE_SEVERITY is an absolute money
     * amount (paid / claim count) rendered as a plain number to 2 decimals.
     */
    static String formatRatio(ReportKey key, BigDecimal ratio) {
        if (ratio == null) return "—";
        if (key == ReportKey.AVERAGE_SEVERITY) {
            return ratio.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        BigDecimal pct = ratio.multiply(BigDecimal.valueOf(100))
                .setScale(1, RoundingMode.HALF_UP);
        return pct.toPlainString() + "%";
    }
}
