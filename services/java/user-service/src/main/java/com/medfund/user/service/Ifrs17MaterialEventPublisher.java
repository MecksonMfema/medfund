package com.medfund.user.service;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Publishes IFRS 17 material events (onerous transitions, CSM-negative,
 * locked-in-curve fallback etc) per Phase 15 §19 (I30).
 *
 * <p>The real implementation is {@link KafkaIfrs17MaterialEventPublisher}
 * (Phase 15 §19), writing to {@code medfund.ifrs17.material-event}. The
 * notification-service Go dispatcher (§20) consumes and fans out per
 * {@code tenant_ifrs17_notification_config} rows (§19 admin surface in
 * tenancy-service). Tests supply a {@code @Primary} Mockito mock to skip
 * the Kafka round-trip.
 */
public interface Ifrs17MaterialEventPublisher {

    /**
     * @param cohortId    the affected cohort (identifies portfolio via join)
     * @param eventType   one of ONEROUS_TRANSITION, CSM_NEGATIVE, LOCKED_IN_CURVE_FALLBACK,
     *                    IBNR_SUB_JOB_STALE, OPENING_BALANCE_AUTO_DERIVED
     * @param severity    INFO | WARN | ERROR
     * @param message     human-readable summary rendered into the notification body
     * @param sourceRunId optional — links back to the report run that triggered the event
     */
    Mono<Void> publish(UUID cohortId, String eventType, String severity,
                       String message, UUID sourceRunId);
}
