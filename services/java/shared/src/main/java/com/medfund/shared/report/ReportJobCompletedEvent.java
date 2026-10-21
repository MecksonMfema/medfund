package com.medfund.shared.report;

import java.util.Map;
import java.util.UUID;

/**
 * Kafka wire schema for {@code medfund.report.job-completed} (Phase 15 §1 —
 * renamed from {@code medfund.actuarial.job-completed}). Published by
 * ai-service's report job runner — one terminal envelope per requested job
 * (status ∈ {@link #STATUS_COMPLETED}, {@link #STATUS_FAILED}). Consumed by
 * finance-service {@code ReportResultConsumer} which writes the row's
 * terminal state to {@code report_job}.
 *
 * <p>{@link #resultJson} carries the compute output for successful jobs;
 * {@link #errorMessage} the reason for failures. {@link #payloadRef} carries
 * the MinIO fallback ref when the inline result would breach the Kafka
 * 1 MB ceiling (§10). {@link #modelVersion} pins the compute-side library
 * version so re-runs can be attributed to the exact library that produced
 * the numbers — Rule 3 (AI decisions must be auditable).
 */
public record ReportJobCompletedEvent(
        int schemaVersion,
        UUID jobId,
        UUID tenantId,
        String reportKey,
        String status,
        Map<String, Object> resultJson,
        String payloadRef,
        String errorMessage,
        String modelVersion,
        String method,
        Map<String, Object> basis,
        String computedAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
}
