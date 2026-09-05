package com.medfund.finance.report.schedule;

import io.r2dbc.spi.Row;

import java.util.UUID;

/**
 * Phase 17 §A.2 — one row from the probe's join of
 * {@code public.tenant_report_schedule} + {@code public.tenants}, carrying
 * every column the fire resolver + orchestrator need to decide whether to
 * fire and to build a {@link ScheduledFireContext}.
 */
public record TenantScheduleFireCandidate(
        UUID scheduleId,
        UUID tenantId,
        String reportKey,
        String cadence,
        Integer hourOfDay,
        Integer dayOfWeek,
        Integer dayOfMonth,
        String reportingCurrency,
        UUID scheduleUpdatedByActorId,
        String scheduleUpdatedByActorEmail,
        String timezone,
        String tenantSlug,
        String tenantName
) {
    static TenantScheduleFireCandidate fromRow(Row row) {
        return new TenantScheduleFireCandidate(
                row.get("id", UUID.class),
                row.get("tenant_id", UUID.class),
                row.get("report_key", String.class),
                row.get("cadence", String.class),
                row.get("hour_of_day", Integer.class),
                row.get("day_of_week", Integer.class),
                row.get("day_of_month", Integer.class),
                row.get("reporting_currency", String.class),
                row.get("updated_by_actor_id", UUID.class),
                row.get("updated_by_actor_email", String.class),
                row.get("timezone", String.class),
                row.get("slug", String.class),
                row.get("name", String.class)
        );
    }
}
