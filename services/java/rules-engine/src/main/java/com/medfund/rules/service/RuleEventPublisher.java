package com.medfund.rules.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.util.Map;
import java.util.UUID;

/**
 * Emits a tiny event to {@code medfund.rules.updated} after every tenant rule
 * mutation. Consumers (claims-service, contributions-service, etc.) subscribe
 * and call {@link TenantRuleLoader#invalidate(UUID)} so the next evaluation
 * rebuilds the local KieContainer from the current database state.
 *
 * <p>The event payload is intentionally minimal — only the tenant id is
 * needed for cache-busting. Consumers don't need to know which rule changed.
 */
@Slf4j
@Service
public class RuleEventPublisher {

    public static final String TOPIC = "medfund.rules.updated";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public RuleEventPublisher(KafkaSender<String, String> kafkaSender, ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> publishRuleChanged(UUID tenantId, UUID ruleId, String action) {
        if (tenantId == null) return Mono.empty();
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "tenantId", tenantId.toString(),
                    "ruleId",   ruleId == null ? "" : ruleId.toString(),
                    "action",   action == null ? "MUTATED" : action
            ));
            var record = new ProducerRecord<>(TOPIC, tenantId.toString(), json);
            return kafkaSender.send(Mono.just(SenderRecord.create(record, tenantId.toString())))
                    .doOnError(e -> log.warn("[rules-events] publish failed: {}", e.getMessage()))
                    .onErrorResume(e -> Mono.empty())
                    .then();
        } catch (Exception e) {
            log.warn("[rules-events] serialize failed: {}", e.getMessage());
            return Mono.empty();
        }
    }
}
