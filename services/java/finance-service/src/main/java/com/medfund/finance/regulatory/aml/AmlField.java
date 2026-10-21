package com.medfund.finance.regulatory.aml;

/**
 * Enumerated cell targets for the AML/STR periodic summary template
 * ({@code AML_STR}). One entry per named-range in the bundled synthetic
 * templates under {@code services/java/shared/src/main/resources/report-templates/aml/}.
 *
 * <p>The report is a periodic summary — one snapshot of the AML posture
 * for a (tenant, period) tuple. Per-STR filings ship separately in
 * Phase 26. Section groupings:
 *
 * <ul>
 *   <li>{@code META_*} — reporting entity + period + currency</li>
 *   <li>{@code THRESHOLD_*} — one row per configured threshold
 *       (transaction type × currency × amount)</li>
 *   <li>{@code ACTIVITY_*} — counts of transactions above / at
 *       threshold in the period</li>
 *   <li>{@code STR_*} — counts of STR filings by status + total
 *       reported amount</li>
 *   <li>{@code SUMMARY_*} — a single derived metric: filed-rate
 *       (STRs filed / above-threshold transactions)</li>
 * </ul>
 *
 * <p>The same enum is populated for the ZW / ZA / US synthetic templates
 * — the templates ship with matching named ranges so the shaper is
 * country-agnostic; the reporting currency is derived from
 * {@code tenant.country_code} via
 * {@link com.medfund.shared.report.regulatory.RegulatoryReportCurrency}.
 */
public enum AmlField {

    // ── Header / meta ──────────────────────────────────────────────────────────
    META_REPORTING_ENTITY_NAME,
    META_REGULATOR_REFERENCE,   // FIU / FIC / FinCEN registration id
    META_COUNTRY,
    META_REPORTING_CURRENCY,
    META_PERIOD_START,
    META_PERIOD_END,

    // ── Configured thresholds ──────────────────────────────────────────────────
    THRESHOLD_PREMIUM,
    THRESHOLD_CLAIM_PAYOUT,
    THRESHOLD_ADVANCE_PAYMENT,
    THRESHOLD_COMMISSION,
    THRESHOLD_OTHER,

    // ── Above-threshold activity counts ───────────────────────────────────────
    ACTIVITY_PREMIUM_COUNT,
    ACTIVITY_PREMIUM_TOTAL,
    ACTIVITY_CLAIM_PAYOUT_COUNT,
    ACTIVITY_CLAIM_PAYOUT_TOTAL,
    ACTIVITY_ADVANCE_PAYMENT_COUNT,
    ACTIVITY_ADVANCE_PAYMENT_TOTAL,
    ACTIVITY_COMMISSION_COUNT,
    ACTIVITY_COMMISSION_TOTAL,
    ACTIVITY_OTHER_COUNT,
    ACTIVITY_OTHER_TOTAL,
    ACTIVITY_ALL_ABOVE_THRESHOLD_COUNT,
    ACTIVITY_ALL_ABOVE_THRESHOLD_TOTAL,

    // ── STR filings in the period ─────────────────────────────────────────────
    STR_RAISED_COUNT,
    STR_REVIEWED_COUNT,
    STR_FILED_COUNT,
    STR_CLOSED_COUNT,
    STR_FILED_TOTAL_AMOUNT,

    // ── Summary ───────────────────────────────────────────────────────────────
    /** Ratio: STR_FILED_COUNT / ACTIVITY_ALL_ABOVE_THRESHOLD_COUNT (4dp HALF_UP). */
    SUMMARY_FILED_RATE
}
