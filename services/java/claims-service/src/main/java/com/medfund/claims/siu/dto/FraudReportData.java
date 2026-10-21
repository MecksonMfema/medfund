package com.medfund.claims.siu.dto;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Payload of the {@code FRAUD_SIU_REPORT} summary envelope (six KPI tiles
 * rendered by {@code clients/angular/.../reports/fraud/fraud-report.component.ts}).
 *
 * <ul>
 *   <li>{@code casesOpened} — total {@code siu_case} rows opened in the window
 *       (any terminal status). Denominator for {@code confirmationRate}.</li>
 *   <li>{@code confirmedCount} — subset closed with
 *       {@code status = CLOSED_CONFIRMED_FRAUD}. Numerator for
 *       {@code confirmationRate}.</li>
 *   <li>{@code savingsComposite} — sum of {@code saved_amount} across all
 *       confirmed cases in the window, converted to the reporting currency
 *       via {@code FxRateReader.convert(...)}. Fail-loud on missing FX per
 *       parent-plan invariant #6.</li>
 *   <li>{@code confirmationRate} — {@code confirmedCount / casesOpened},
 *       rounded to 4 decimal places. Returns {@code 0.0000} when
 *       {@code casesOpened == 0}.</li>
 *   <li>{@code avgCycleTimeDays} (§B Phase 11) — mean days between
 *       {@code opened_at} and {@code closed_at} for cases closed in the
 *       window. Feeds the operational-efficiency tile.</li>
 *   <li>{@code reopenedCount} (§B Phase 11) — count of `REOPENED →
 *       UNDER_REVIEW` transitions observed in the window (via
 *       {@code siu_case_note} `note_type = 'STATUS_CHANGE'` bodies).
 *       Feeds the quality-of-triage tile.</li>
 *   <li>{@code savingsPerCurrency} — savings totals in native currency for
 *       the "per-currency" breakdown the envelope shows below the tiles.</li>
 * </ul>
 */
public record FraudReportData(
        long casesOpened,
        long confirmedCount,
        BigDecimal savingsComposite,
        BigDecimal confirmationRate,
        BigDecimal avgCycleTimeDays,
        long reopenedCount,
        Map<String, BigDecimal> savingsPerCurrency
) {
}
