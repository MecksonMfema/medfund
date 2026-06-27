package com.medfund.shared.scheduler;

import java.time.Instant;
import java.util.UUID;

/**
 * Projection for the header bell dropdown. Carries the run row's
 * status fields plus the friendly config name + machine job type from
 * scheduled_job_configs, so the UI shows "Billing commit · September
 * billing" instead of "Job a1b2c3d4".
 *
 * <p>Distinct type from {@link ScheduledJobRun} because Spring Data
 * R2DBC entity rules don't apply — this is a read-only join projection
 * with no PK / no @Table, never written. Kept in the same package so
 * the controller can hand it back without an export-import dance.
 */
public record ScheduledJobRunSummary(
        UUID id,
        UUID configId,
        String configName,
        String jobType,
        UUID tenantId,
        Instant startedAt,
        Instant endedAt,
        Long durationMs,
        String status,
        String triggerKind,
        String errorMessage
) {}
