package com.medfund.shared.report;

import java.util.Map;
import java.util.UUID;

/**
 * Kafka wire schema for {@code medfund.report.job-requested} (Phase 15 §1 —
 * renamed from {@code medfund.actuarial.job-requested}). Published by
 * finance-service after shaping a triangle / cohort / exposure / IFRS 17
 * chunk payload; consumed by ai-service's report job runner.
 *
 * <p>Java ↔ Python contract — all payload-carrier maps are optional so a
 * single wire shape covers every {@link ReportKey}:
 * <ul>
 *   <li>{@link #triangle} — IBNR_TRIANGLE, LOSS_TRIANGLE (Phase 14)</li>
 *   <li>{@link #cohort} — PERSISTENCY_STUDY, LAPSE_STUDY (Phase 14)</li>
 *   <li>{@link #exposure} — MORTALITY_STUDY, MORBIDITY_STUDY (Phase 14)</li>
 *   <li>{@link #ifrs17Json} — IFRS17_* keys (Phase 15 §11+)</li>
 * </ul>
 *
 * <p>{@link #parentJobId} links a chunk back to its parent report job for the
 * IFRS 17 aggregator pattern (§18) and the IBNR sub-job orchestrator (§24).
 * {@link #payloadRef} is the MinIO fallback ref when the inline payload would
 * breach the Kafka 1 MB ceiling (§10) — either the map fields OR
 * {@link #payloadRef} is populated, never both.
 *
 * <p>{@link #schemaVersion} bumps only on breaking changes; additive fields
 * (new report keys, new payload carriers) keep the version.
 */
public record ReportJobRequestedEvent(
        int schemaVersion,
        UUID jobId,
        UUID tenantId,
        UUID parentJobId,
        String reportKey,
        Map<String, Object> params,
        Map<String, Object> triangle,
        Map<String, Object> exposure,
        Map<String, Object> cohort,
        Map<String, Object> ifrs17Json,
        String payloadRef,
        UUID requestedBy,
        String requestedByEmail) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
