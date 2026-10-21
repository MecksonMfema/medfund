package com.medfund.finance.ifrs17.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.UUID;

/**
 * Phase 15 §19 (I30) — finance-service Kafka producer for IFRS 17 material
 * events, symmetric to
 * {@code com.medfund.user.service.KafkaIfrs17MaterialEventPublisher} in
 * user-service. Both write to the same
 * {@code medfund.ifrs17.material-event} topic; the notification-service Go
 * dispatcher (§20) consumes and fans out per
 * {@code tenant_ifrs17_notification_config} rows.
 *
 * <p>Called from {@link com.medfund.finance.ifrs17.service.Ifrs17JobAggregator}
 * on aggregation completion when a chunk result carries a material signal
 * ({@code csmNegative=true}, {@code lockedInCurveFallback=true}, or the
 * {@code IBNR_SUB_JOB_STALE} envelope warning per §18 Deviation 5).
 *
 * <p>Wire shape mirrors the user-service publisher exactly: flat
 * {@code Map<String,String>} body, tenant as partition key, best-effort
 * publish (errors log and continue — a lost notification is not worth
 * blocking the aggregator's commit).
 */
@Slf4j
@Component
public class Ifrs17MaterialEventPublisher {

    public static final String TOPIC = "medfund.ifrs17.material-event";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public Ifrs17MaterialEventPublisher(KafkaSender<String, String> kafkaSender,
                                        ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    /**
     * @param tenantId    the tenant to fan out to (partition key)
     * @param cohortId    optional — set for cohort-scoped events (ONEROUS_TRANSITION,
     *                    CSM_NEGATIVE, LOCKED_IN_CURVE_FALLBACK,
     *                    OPENING_BALANCE_AUTO_DERIVED); null for job-scoped
     *                    events (IBNR_SUB_JOB_STALE)
     * @param eventType   one of the {@code tenant_ifrs17_notification_config}
     *                    CHECK-allowed types
     * @param severity    {@code INFO | WARN | ERROR}
     * @param message     human-readable summary rendered into the notification
     * @param sourceRunId the parent {@code report_job.job_id} the event surfaced
     *                    from
     */
    public Mono<Void> publish(String tenantId, UUID cohortId, String eventType,
                              String severity, String message, UUID sourceRunId) {
        if (tenantId == null || tenantId.isBlank()) {
            log.warn("[ifrs17-material-event] no tenantId — dropping type={} severity={}",
                    eventType, severity);
            return Mono.empty();
        }
        var body = new LinkedHashMap<String, String>();
        body.put("event", "IFRS17_MATERIAL_EVENT");
        body.put("schemaVersion", "1");
        body.put("tenantId", tenantId);
        body.put("cohortId", cohortId != null ? cohortId.toString() : "");
        body.put("eventType", eventType != null ? eventType : "");
        body.put("severity", severity != null ? severity : "INFO");
        body.put("message", message != null ? message : "");
        body.put("sourceRunId", sourceRunId != null ? sourceRunId.toString() : "");
        body.put("occurredAt", OffsetDateTime.now().toString());
        String payload;
        try {
            payload = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            log.error("[ifrs17-material-event] serialise failed tenant={} type={}: {}",
                    tenantId, eventType, e.getMessage(), e);
            return Mono.empty();
        }
        var record = new ProducerRecord<>(TOPIC, tenantId, payload);
        return kafkaSender.send(Mono.just(SenderRecord.create(record, tenantId)))
                .next()
                .then()
                .doOnError(e -> log.error(
                        "[ifrs17-material-event] publish failed tenant={} type={}: {}",
                        tenantId, eventType, e.getMessage(), e))
                .onErrorResume(e -> Mono.empty());
    }
}
