package com.medfund.shared.actuarial;

import java.util.Map;
import java.util.UUID;

/**
 * Kafka wire schema for {@code medfund.actuarial.job-requested}. Published by
 * finance-service {@code ActuarialJobPublisher} after shaping a triangle /
 * cohort / exposure payload; consumed by ai-service's
 * {@code ActuarialJobRunner}.
 *
 * <p>Java ↔ Python contract — all three payload-carrier maps are optional so a
 * single wire shape covers all six {@code ReportKey}s under
 * {@code ReportFamily.ACTUARIAL}:
 * <ul>
 *   <li>{@link #triangle} — IBNR_TRIANGLE, LOSS_TRIANGLE (Phase 9)</li>
 *   <li>{@link #cohort} — PERSISTENCY_STUDY, LAPSE_STUDY (Phase 11-12)</li>
 *   <li>{@link #exposure} — MORTALITY_STUDY, MORBIDITY_STUDY (Phase 13-14)</li>
 * </ul>
 *
 * <p>{@link #schemaVersion} bumps only on breaking changes; additive fields
 * (new report keys, new payload carriers) keep the version.
 */
public record ActuarialJobRequestedEvent(
        int schemaVersion,
        UUID jobId,
        UUID tenantId,
        String reportKey,
        Map<String, Object> params,
        Map<String, Object> triangle,
        Map<String, Object> exposure,
        Map<String, Object> cohort,
        UUID requestedBy,
        String requestedByEmail) {

    public static final int CURRENT_SCHEMA_VERSION = 1;
}
