package com.medfund.finance.report.schedule;

import com.medfund.shared.report.ReportKey;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Phase 17 §A.2 — the uniform input every {@link ScheduledReportShapeAdapter}
 * consumes. The probe/orchestrator normalises the heterogeneous per-report
 * shape-service signatures into this envelope so adapters stay one-liners.
 *
 * <p>{@code scheduleUpdatedByActor*} pass through the human actor who last
 * touched the schedule row — Phase 5 owner-service adapters forward these to
 * the render endpoint so downstream {@code publishDataAccess(...)} calls carry
 * a real actor identity, not a synthetic "system" one.
 *
 * <p>Phase 19 §B Phase 12 added {@code scheduleParams} — the deserialised
 * {@code public.tenant_report_schedule.params} JSONB, opaque per key. Only
 * {@code FRAUD_SIU_REPORT} reads it today ({@code includeSensitiveSheets}
 * per FR12). Nullable + a legacy constructor delegates to {@code null} so
 * pre-Phase-12 test/production callers stay compiling.
 */
public record ScheduledFireContext(
        UUID tenantId,
        ReportKey reportKey,
        UUID scheduleId,
        LocalDate periodStart,
        LocalDate periodEnd,
        LocalDate asOf,
        String reportingCurrency,
        String cadenceLabel,
        OffsetDateTime firedAt,
        UUID scheduleUpdatedByActorId,
        String scheduleUpdatedByActorEmail,
        Map<String, Object> scheduleParams
) {
    public ScheduledFireContext(
            UUID tenantId,
            ReportKey reportKey,
            UUID scheduleId,
            LocalDate periodStart,
            LocalDate periodEnd,
            LocalDate asOf,
            String reportingCurrency,
            String cadenceLabel,
            OffsetDateTime firedAt,
            UUID scheduleUpdatedByActorId,
            String scheduleUpdatedByActorEmail) {
        this(tenantId, reportKey, scheduleId, periodStart, periodEnd, asOf,
                reportingCurrency, cadenceLabel, firedAt,
                scheduleUpdatedByActorId, scheduleUpdatedByActorEmail, null);
    }
}
