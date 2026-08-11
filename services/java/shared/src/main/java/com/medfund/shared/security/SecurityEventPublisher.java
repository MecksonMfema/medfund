package com.medfund.shared.security;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes {@link SecurityEventMessage} payloads to the
 * {@code medfund.security.events} Kafka topic. Reactive analogue of the
 * fire-and-forget KafkaProducer wrapper that runs inside the Keycloak JVM
 * (which cannot depend on this module); both write the same wire shape to
 * the same topic and the audit-service consumer treats them identically.
 *
 * <p>Emits {@code DATA_ACCESS} events on every report export (XLSX / PDF)
 * per Rule 9 in {@code .claude/CLAUDE.md}. See {@link #publishDataAccess}
 * for the reporting-suite entry point.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SecurityEventPublisher {

    static final String TOPIC = "medfund.security.events";

    /** Event type recorded for every report export — XLSX, PDF, CSV. */
    public static final String EVENT_TYPE_DATA_ACCESS = "DATA_ACCESS";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    /**
     * Fire the event to Kafka. Errors are logged but not propagated —
     * a failed audit publish must never abort a user-facing operation
     * (see Rule 9 in {@code .claude/CLAUDE.md}: security events are
     * important, but a broker hiccup can't be allowed to fail a PDF
     * download or block a report export downstream).
     */
    public Mono<Void> publish(SecurityEventMessage event) {
        try {
            String value = objectMapper.writeValueAsString(event);
            var record = new ProducerRecord<>(TOPIC, event.userId(), value);
            return kafkaSender.send(Mono.just(SenderRecord.create(record, event.id())))
                    .doOnError(e -> log.error("Failed to publish security event {}: {}",
                            event.eventType(), e.getMessage()))
                    .then()
                    .onErrorResume(e -> Mono.empty());
        } catch (JsonProcessingException e) {
            log.error("Failed to serialise security event {}: {}",
                    event.eventType(), e.getMessage());
            return Mono.empty();
        }
    }

    /**
     * Emit a {@code DATA_ACCESS} event for a report export. The details map
     * is serialised as compact JSON — callers pass the {@code reportKey}
     * plus any per-report contextual fields (period, filters, row count).
     *
     * <p>{@code tenantId} and {@code actorId} accept {@code null} for
     * platform-scope endpoints; the audit-service store treats blank tenant
     * as platform-scope and blank actor as system-originated.
     */
    public Mono<Void> publishDataAccess(String tenantId,
                                        String actorId,
                                        String actorEmail,
                                        String reportKey,
                                        Map<String, Object> details) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("reportKey", reportKey);
        if (details != null) merged.putAll(details);
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(merged);
        } catch (JsonProcessingException e) {
            log.warn("[security-event] details serialise failed for report {}: {}",
                    reportKey, e.getMessage());
            detailsJson = "{}";
        }
        SecurityEventMessage msg = new SecurityEventMessage(
                UUID.randomUUID().toString(),
                tenantId != null ? tenantId : "",
                EVENT_TYPE_DATA_ACCESS,
                actorId != null ? actorId : "",
                actorEmail != null ? actorEmail : "",
                "",
                "",
                detailsJson,
                Instant.now().toString()
        );
        return publish(msg);
    }
}
