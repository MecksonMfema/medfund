package com.medfund.shared.actuarial;

import java.util.Map;
import java.util.UUID;

/**
 * Kafka wire schema for {@code medfund.actuarial.job-completed}. Published by
 * ai-service's {@code ActuarialJobRunner} — one terminal envelope per
 * requested job (status ∈ {@code completed}, {@code failed}). Consumed by
 * finance-service {@code ActuarialResultConsumer} which writes the row's
 * terminal state to {@code actuarial_report_job}.
 *
 * <p>{@link #resultJson} carries the compute output for successful jobs;
 * {@link #errorMessage} the reason for failures. {@link #modelVersion} pins
 * the chainladder-python version so re-runs can be attributed to the exact
 * library that produced the numbers — Rule 3 (AI decisions must be
 * auditable).
 */
public record ActuarialJobCompletedEvent(
        int schemaVersion,
        UUID jobId,
        UUID tenantId,
        String reportKey,
        String status,
        Map<String, Object> resultJson,
        String errorMessage,
        String modelVersion,
        String method,
        Map<String, Object> basis,
        String computedAt) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
}
