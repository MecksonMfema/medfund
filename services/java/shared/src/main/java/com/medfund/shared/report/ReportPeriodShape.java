package com.medfund.shared.report;

/**
 * Whether a scheduled report covers a completed prior period or a snapshot as
 * of fire time. Used by the Phase 17 scheduler ({@code ScheduledReportProbe} +
 * {@code ScheduledReportOrchestrator} in finance-service) to derive
 * (periodStart, periodEnd, asOf) from (cadence, firedAt, shape).
 *
 * <p>{@link #PREVIOUS_COMPLETE_PERIOD} — for period reports like
 * COMMISSION_STATEMENT, LOSS_RATIO, POLICY_MOVEMENT: the fire covers the
 * previously-completed calendar unit (last week/month/quarter/year).
 *
 * <p>{@link #AS_OF_FIRE_TIME} — for snapshot reports like AGED_DEBTORS and
 * CASH_FLOW_FORECAST_13W: (periodStart, periodEnd, asOf) all equal firedAt.
 */
public enum ReportPeriodShape {
    PREVIOUS_COMPLETE_PERIOD,
    AS_OF_FIRE_TIME
}
