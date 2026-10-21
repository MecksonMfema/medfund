package com.medfund.shared.report;

import java.time.Instant;
import java.util.UUID;

/**
 * Phase 17 §S2 delivery envelope — emitted by finance-service
 * {@code ScheduledReportOrchestrator} on successful XLSX upload; consumed by
 * the notification-service {@code internal/report/dispatcher.go} package.
 *
 * <p>The payload deliberately carries only a MinIO object reference
 * ({@link #xlsxRef}) and metadata — the XLSX bytes themselves stay in
 * {@code medfund-report-payloads}, keeping envelope size well under the
 * Kafka message ceiling. The consumer fetches on demand and either MIME-
 * attaches (≤10 MB) or renders a signed-download link (>10 MB).
 *
 * <p>{@link #cadenceLabel}, {@link #periodStart}, {@link #periodEnd} are the
 * pre-rendered strings the email templates need — no cadence lookup or date
 * arithmetic happens in Go.
 */
public record ReportDeliveryEvent(
        UUID jobId,
        UUID scheduleId,
        UUID tenantId,
        String reportKey,
        String xlsxRef,
        String sha256,
        long sizeBytes,
        String cadenceLabel,
        String periodStart,
        String periodEnd,
        String reportingCurrency,
        Instant occurredAt,
        int schemaVersion
) {
    public static final String TOPIC = "medfund.notification.report-delivery";
    public static final int SCHEMA_VERSION = 1;
}
