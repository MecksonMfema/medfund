package com.medfund.finance.regulatory.aml;

import java.math.BigDecimal;
import java.util.EnumMap;
import java.util.Map;

/**
 * Pre-aggregated raw counts + amounts for the AML/STR periodic summary.
 * Two dimensions:
 *
 * <ul>
 *   <li>{@link #aboveThresholdCount} / {@link #aboveThresholdTotal} — one
 *       entry per {@link ActivityCategory} counting transactions in the
 *       period whose amount is ≥ the configured tenant threshold for
 *       that category.</li>
 *   <li>{@link #strCountByStatus} — count of {@code SuspiciousTransactionAlert}
 *       rows in each workflow status raised during the period.</li>
 *   <li>{@link #strFiledTotalAmount} — sum of {@code amountNative} across
 *       every FILED alert whose {@code filedAt} falls in the period.</li>
 * </ul>
 *
 * <p>Provider implementations must return defensively-defaulted maps
 * (never {@code null}), so the calculator can compute totals without
 * per-entry null checks.
 */
public record AmlSummaryRawData(
        String reportingEntityName,
        String regulatorReference,
        Map<ActivityCategory, Long> aboveThresholdCount,
        Map<ActivityCategory, BigDecimal> aboveThresholdTotal,
        Map<StrStatus, Long> strCountByStatus,
        BigDecimal strFiledTotalAmount) {

    public AmlSummaryRawData {
        // Defensive defaults — the calculator relies on these being non-null.
        if (aboveThresholdCount == null) aboveThresholdCount = new EnumMap<>(ActivityCategory.class);
        if (aboveThresholdTotal == null) aboveThresholdTotal = new EnumMap<>(ActivityCategory.class);
        if (strCountByStatus == null)    strCountByStatus    = new EnumMap<>(StrStatus.class);
        if (strFiledTotalAmount == null) strFiledTotalAmount = BigDecimal.ZERO;
    }

    /** Categories the AML report tracks — mirror the {@code transaction_type} enum on
     *  {@code suspicious_transaction_alert} + {@code tenant_aml_threshold_config}. */
    public enum ActivityCategory {
        PREMIUM, CLAIM_PAYOUT, ADVANCE_PAYMENT, COMMISSION, OTHER
    }

    /** Workflow statuses aggregated for the STR side. */
    public enum StrStatus { RAISED, REVIEWED, FILED, CLOSED }
}
