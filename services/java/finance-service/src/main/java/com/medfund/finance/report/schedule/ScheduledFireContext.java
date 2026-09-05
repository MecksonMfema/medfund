package com.medfund.finance.report.schedule;

import com.medfund.shared.report.ReportKey;

import java.time.LocalDate;
import java.time.OffsetDateTime;
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
        String scheduleUpdatedByActorEmail
) {}
