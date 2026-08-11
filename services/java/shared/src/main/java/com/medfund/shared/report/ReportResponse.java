package com.medfund.shared.report;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * Uniform envelope for every family-level report response. The typed
 * {@code data} block carries the report-specific payload; {@code perCurrency}
 * carries the native-currency breakdown that G6 mandates (never lose the
 * per-currency detail behind a converted reporting-currency total).
 *
 * @param reportKey         canonical {@link ReportKey}#name()
 * @param period            the reporting window this response covers
 * @param reportingCurrency ISO-4217 code the aggregated {@code data} block
 *                          is expressed in (tenant default, or the
 *                          {@code ?reportingCurrency=} override)
 * @param data              report-specific payload converted to
 *                          {@code reportingCurrency}
 * @param perCurrency       native-currency breakdown keyed by ISO-4217
 *                          code — never converted, always the ledger truth
 * @param generatedAt       server clock at response build time
 */
public record ReportResponse<T>(
        String reportKey,
        ReportPeriod period,
        String reportingCurrency,
        T data,
        Map<String, T> perCurrency,
        OffsetDateTime generatedAt
) {
    public static <T> ReportResponse<T> of(ReportKey key,
                                           ReportPeriod period,
                                           String reportingCurrency,
                                           T data,
                                           Map<String, T> perCurrency) {
        return new ReportResponse<>(
                key.name(), period, reportingCurrency, data, perCurrency, OffsetDateTime.now()
        );
    }
}
