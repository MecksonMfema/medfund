package com.medfund.shared.report;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/**
 * Uniform envelope for every family-level report response. The typed
 * {@code data} block carries the report-specific payload; {@code perCurrency}
 * carries the native-currency ledger totals (G6), {@code fxRates} carries
 * best-effort native→reporting conversion rates (G28), and {@code warnings}
 * names any currencies whose rate was missing so the client can render a
 * warning banner without failing the report.
 *
 * <p>Signature reshaped per Phase 1 grilling G17 — {@code perCurrency} no
 * longer shares {@code T} with the payload; it's always a fixed shape so
 * paged and aggregate reports can both express native totals uniformly.
 *
 * @param reportKey         canonical {@link ReportKey}#name()
 * @param period            reporting window; nullable per G20 — periodless
 *                          controllers (current-state snapshots) simply
 *                          don't populate it
 * @param reportingCurrency ISO-4217 code the {@code data} block is expressed
 *                          in when the server converts (tenant default, or
 *                          the {@code ?reportingCurrency=} override). Rows
 *                          on paged reports stay native (G25) — this is the
 *                          currency of any converted scalar totals.
 * @param data              report-specific payload
 * @param perCurrency       native-currency ledger totals keyed by ISO-4217
 *                          code — never converted, always the ledger truth,
 *                          fixed shape independent of {@code T}
 * @param fxRates           best-effort native→reporting rates keyed by
 *                          native ISO-4217 code; a missing entry means the
 *                          FX lookup failed for that currency and the client
 *                          should skip conversion for it (see {@code warnings})
 * @param warnings          human-readable notes such as
 *                          "FX not available for ZAR→USD as of 2026-08-11"
 * @param generatedAt       server clock at response build time
 */
public record ReportResponse<T>(
        String reportKey,
        ReportPeriod period,
        String reportingCurrency,
        T data,
        Map<String, PerCurrencyTotal> perCurrency,
        Map<String, BigDecimal> fxRates,
        List<String> warnings,
        OffsetDateTime generatedAt
) {
    public static <T> ReportResponse<T> of(ReportKey key,
                                           ReportPeriod period,
                                           String reportingCurrency,
                                           T data,
                                           Map<String, PerCurrencyTotal> perCurrency,
                                           Map<String, BigDecimal> fxRates,
                                           List<String> warnings) {
        return new ReportResponse<>(
                key.name(),
                period,
                reportingCurrency,
                data,
                perCurrency != null ? perCurrency : Map.of(),
                fxRates    != null ? fxRates    : Map.of(),
                warnings   != null ? warnings   : List.of(),
                OffsetDateTime.now()
        );
    }
}
