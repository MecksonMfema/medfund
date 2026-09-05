package com.medfund.shared.report;

/**
 * Reporting cadence for a scheduled or regulator report — the interval on
 * which the platform delivers it. Consumed by {@link ReportCadenceCatalog}
 * to derive regulator due dates ({@code RegulatoryDueDateScanner} — Phase 16
 * REG20) and by the Phase 17 {@code ScheduledReportProbe} +
 * {@code ScheduledReportFireResolver} to decide which tenant-configured
 * schedules match the current hour in the tenant's timezone.
 *
 * <p>{@code WEEKLY} is Phase 17 scheduling only — the regulator cadence
 * catalogue does not use it.
 *
 * <p>{@code EVENT_DRIVEN} sits alongside the periodic cadences for the
 * per-STR AML filing path — the AML/STR key is exposed as periodic in the
 * catalog because the tenant-visible summary is the cadenced surface; the
 * per-STR export path is a separate workflow (§D). Regulator-only; not
 * selectable by tenants in the Phase 17 schedule form.
 */
public enum ReportCadence {
    WEEKLY,
    MONTHLY,
    QUARTERLY,
    ANNUAL,
    EVENT_DRIVEN
}
