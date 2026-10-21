package com.medfund.finance.regulatory.service;

import com.medfund.shared.report.ReportKey;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Canonical intermediate shape for a Phase-16 regulator report between
 * the per-regulator shaper (which pulls raw data from claims-service,
 * contributions-service, finance-service internals) and the
 * {@code RegulatoryCellMap} consumer (which populates named ranges in
 * the bundled XLSX template).
 *
 * <p>The {@link #sections()} map is regulator-agnostic — keys are
 * regulator-defined section names (e.g. {@code "revenue_account"},
 * {@code "solvency"}, {@code "reinsurance_recoverables"}); values are
 * flat {@code Map<String,Object>} rows keyed by the same enum names the
 * cell map exposes. Order is preserved for deterministic XLSX writes.
 *
 * <p>{@link #reportingCurrency()} is the resolved regulator currency
 * (via {@link RegulatoryReportCurrency#resolveOrThrow}) — all monetary
 * values in {@link #sections()} have already been converted through
 * {@link RegulatoryFxPolicy}.
 */
public record RegulatoryReportData(
        ReportKey reportKey,
        UUID tenantId,
        LocalDate periodStart,
        LocalDate periodEnd,
        String reportingCurrency,
        Map<String, Map<String, Object>> sections) {

    public RegulatoryReportData {
        // Defensive copy so callers can't mutate the intermediate after emission.
        sections = sections != null ? new LinkedHashMap<>(sections) : new LinkedHashMap<>();
    }

    public static Builder builder(ReportKey key, UUID tenantId,
                                  LocalDate periodStart, LocalDate periodEnd,
                                  String reportingCurrency) {
        return new Builder(key, tenantId, periodStart, periodEnd, reportingCurrency);
    }

    public static final class Builder {
        private final ReportKey key;
        private final UUID tenantId;
        private final LocalDate periodStart;
        private final LocalDate periodEnd;
        private final String reportingCurrency;
        private final Map<String, Map<String, Object>> sections = new LinkedHashMap<>();

        private Builder(ReportKey key, UUID tenantId,
                        LocalDate periodStart, LocalDate periodEnd,
                        String reportingCurrency) {
            this.key = key;
            this.tenantId = tenantId;
            this.periodStart = periodStart;
            this.periodEnd = periodEnd;
            this.reportingCurrency = reportingCurrency;
        }

        public Builder section(String name, Map<String, Object> values) {
            sections.put(name, new LinkedHashMap<>(values));
            return this;
        }

        public Builder put(String section, String key, Object value) {
            sections.computeIfAbsent(section, k -> new LinkedHashMap<>()).put(key, value);
            return this;
        }

        public RegulatoryReportData build() {
            return new RegulatoryReportData(key, tenantId, periodStart, periodEnd,
                    reportingCurrency, sections);
        }
    }
}
