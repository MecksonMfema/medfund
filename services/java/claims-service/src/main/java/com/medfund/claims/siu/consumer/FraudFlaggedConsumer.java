package com.medfund.claims.siu.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.claims.siu.service.FraudFlagService;
import com.medfund.claims.siu.service.SiuCaseService;
import com.medfund.shared.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;

/**
 * Consumes {@code FRAUD_FLAG_EMITTED} events from
 * {@code medfund.claims.fraud-flagged} (published by ai-service per
 * Phase 19 §A Phase 3). Persists one fraud_flag row per Rule 3, then hands
 * off to {@link SiuCaseService#evaluateTriage} which decides whether to
 * auto-open an SIU case.
 *
 * <p>Ack pattern: {@code .doOnSuccess} + {@code .onErrorResume} with
 * explicit {@code .acknowledge()} on both paths per
 * {@code bug_reactor_kafka_ack_swallow} — never {@code .doOnTerminate}
 * (fires on error and silently drops failed records). Reference:
 * {@code services/java/rules-engine/.../consumer/RuleChangeConsumer.java}.
 *
 * <p>Guarded by {@code @ConditionalOnBean(ReceiverOptions.class)} so tests
 * without a Kafka broker (e.g. slice tests) skip the wire-up.
 */
@Slf4j
@Component
@ConditionalOnBean(ReceiverOptions.class)
@RequiredArgsConstructor
public class FraudFlaggedConsumer {

    static final String TOPIC = "medfund.claims.fraud-flagged";
    static final String GROUP_ID = "claims-service.fraud-flagged";

    private final ReceiverOptions<String, String> receiverOptions;
    private final FraudFlagService fraudFlagService;
    private final SiuCaseService siuCaseService;
    private final ObjectMapper objectMapper;

    @Value("${fraud.consumer.enabled:true}")
    private boolean enabled;

    @PostConstruct
    void start() {
        if (!enabled) {
            log.info("FraudFlaggedConsumer disabled via fraud.consumer.enabled=false");
            return;
        }
        ReceiverOptions<String, String> options = receiverOptions
                .consumerProperty(
                        org.apache.kafka.clients.consumer.ConsumerConfig.GROUP_ID_CONFIG,
                        GROUP_ID)
                .subscription(Collections.singleton(TOPIC));

        KafkaReceiver.create(options).receive()
                .flatMap(record -> processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(e -> {
                            log.error("[fraud-flagged] processing failed key={} offset={}",
                                    record.key(),
                                    record.receiverOffset().offset(), e);
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                        .maxBackoff(Duration.ofMinutes(2))
                        .doBeforeRetry(sig -> log.warn(
                                "[fraud-flagged] consumer restarting after error: {}",
                                sig.failure().getMessage())))
                .subscribe();
        log.info("FraudFlaggedConsumer subscribed topic={} group={}", TOPIC, GROUP_ID);
    }

    Mono<Void> processEvent(String json) {
        try {
            JsonNode event = objectMapper.readTree(json);
            String tenantId = event.hasNonNull("tenantId")
                    ? event.get("tenantId").asText() : null;
            Mono<Void> chain = fraudFlagService.persist(event)
                    .flatMap(siuCaseService::evaluateTriage);
            // Propagate tenantId into the reactor context so downstream
            // AuditPublisher.publish calls resolve tenantId from
            // TenantContext.get(ctx) instead of stamping the audit row
            // with "unknown".
            if (tenantId != null) {
                chain = chain.contextWrite(ctx -> TenantContext.put(ctx, tenantId));
            }
            return chain;
        } catch (Exception ex) {
            return Mono.error(ex);
        }
    }
}
