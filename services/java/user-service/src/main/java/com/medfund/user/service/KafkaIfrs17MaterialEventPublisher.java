package com.medfund.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.tenant.TenantContext;
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
 * Phase 15 §19 (I30) — real Kafka producer implementation of
 * {@link Ifrs17MaterialEventPublisher}. Fans out on
 * {@code medfund.ifrs17.material-event} for the notification-service Go
 * dispatcher (§20) to consume and deliver per
 * {@code tenant_ifrs17_notification_config} rows.
 *
 * <p>Replaces the Phase 4 no-op stand-in. Tests that don't want a real
 * Kafka round-trip mock this bean via {@code @Primary} in a
 * {@code @TestConfiguration} — the existing Phase 4/5/15 ITs already do so.
 *
 * <p>Wire shape follows the {@link PolicyIssuedPublisher} /
 * {@code PolicyStatusChangedPublisher} precedent: flat
 * {@code Map<String,String>} body over {@link KafkaSender}, nullable fields
 * emit as empty strings, partition key is the tenant so a single tenant's
 * events stay ordered on one partition.
 */
@Slf4j
@Component
public class KafkaIfrs17MaterialEventPublisher implements Ifrs17MaterialEventPublisher {

    public static final String TOPIC = "medfund.ifrs17.material-event";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public KafkaIfrs17MaterialEventPublisher(KafkaSender<String, String> kafkaSender,
                                             ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> publish(UUID cohortId, String eventType, String severity,
                              String message, UUID sourceRunId) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            if (tenantId == null || tenantId.isBlank()) {
                // Publishing an ifrs17 material event without a tenant would leave
                // the dispatcher unable to resolve recipients — log and drop rather
                // than write into the void.
                log.warn("[ifrs17-material-event] no tenant on reactor context — dropping "
                        + "cohortId={} type={} severity={}", cohortId, eventType, severity);
                return Mono.<Void>empty();
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
            try {
                String json = objectMapper.writeValueAsString(body);
                var record = new ProducerRecord<>(TOPIC, tenantId, json);
                var senderRecord = SenderRecord.create(record, tenantId);
                return kafkaSender.send(Mono.just(senderRecord))
                        .doOnError(e -> log.error(
                                "Failed to publish ifrs17 material event tenant={} type={}: {}",
                                tenantId, eventType, e.getMessage(), e))
                        .then();
            } catch (Exception e) {
                log.error("Failed to serialize ifrs17 material event tenant={} type={}: {}",
                        tenantId, eventType, e.getMessage(), e);
                return Mono.empty();
            }
        });
    }
}
