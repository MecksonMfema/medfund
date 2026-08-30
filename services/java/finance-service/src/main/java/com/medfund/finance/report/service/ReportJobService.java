package com.medfund.finance.report.service;

import com.medfund.finance.report.entity.ReportJob;
import com.medfund.shared.report.ReportFamily;
import com.medfund.shared.report.ReportKey;

/**
 * Cross-cutting helpers for the generic async report pipeline (Phase 15 §3).
 * Central home for retention-class classification so every publisher agrees
 * on which report keys count as statutory (7-year keep) vs operational
 * (90 days + top-N-per-key trim).
 *
 * <p>Kept as static helpers rather than a Spring bean so callers in the
 * legacy actuarial package and the new IFRS 17 aggregator can reach it
 * without wiring an extra dependency into every service. Populated further
 * in §17 with submit / chunk fan-out orchestration; for now this class is
 * a single-utility surface driven by the plan's §3 requirement.
 */
public final class ReportJobService {

    private ReportJobService() {
        // helper class — no instances
    }

    /**
     * Classify the retention class for a given report key. IFRS 17 and other
     * regulatory reports must be retained for 7 years per statutory
     * requirements (I28); everything else follows the Phase 14 operational
     * default (90 days + top-20 runs per tenant × key).
     *
     * @param reportKey the report key name (matches {@link ReportKey#name()});
     *                  unknown keys fall back to {@code OPERATIONAL_90D}
     */
    public static String classifyRetention(String reportKey) {
        return ReportKey.parse(reportKey)
                .map(ReportKey::getFamily)
                .filter(family -> family == ReportFamily.REGULATORY)
                .map(f -> ReportJob.RETENTION_STATUTORY_7Y)
                .orElse(ReportJob.RETENTION_OPERATIONAL_90D);
    }
}
