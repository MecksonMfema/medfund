package com.medfund.shared.report;

/**
 * Reporting cadence for a regulator report — the interval on which a tenant
 * files it. Consumed by {@link ReportCadenceCatalog} to derive due dates for
 * banner rendering (Angular) and threshold-based push notifications
 * ({@code RegulatoryDueDateScanner} — Phase 8).
 *
 * <p>{@code EVENT_DRIVEN} sits alongside the periodic cadences for the
 * per-STR AML filing path — the AML/STR key is exposed as periodic in the
 * catalog because the tenant-visible summary is the cadenced surface; the
 * per-STR export path is a separate workflow (§D).
 */
public enum ReportCadence {
    MONTHLY,
    QUARTERLY,
    ANNUAL,
    EVENT_DRIVEN
}
