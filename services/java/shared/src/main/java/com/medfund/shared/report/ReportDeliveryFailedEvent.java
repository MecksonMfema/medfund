package com.medfund.shared.report;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 17 §S2 failure envelope — emitted by finance-service
 * {@code ScheduledReportOrchestrator} on any per-fire failure; consumed by
 * notification-service to alert the tenant admin.
 *
 * <p>{@link #failureStage} is one of
 * {@code SHAPE | MINIO_UPLOAD | KAFKA_PUBLISH | DISPATCH | SMTP}.
 * {@link #errorSummary} is a short human-facing message extracted from the
 * exception; full stack traces stay in the finance-service logs.
 */
public record ReportDeliveryFailedEvent(
        UUID jobId,
        UUID scheduleId,
        UUID tenantId,
        String reportKey,
        String periodStart,
        String periodEnd,
        String failureStage,
        String errorSummary,
        Instant occurredAt,
        int schemaVersion
) {
    public static final String TOPIC = "medfund.notification.report-delivery-failed";
    public static final int SCHEMA_VERSION = 1;
}
