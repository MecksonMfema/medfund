package com.medfund.rules.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.rules.service.TenantRuleLoader;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Listens to {@code medfund.rules.updated} (published by rules-service after
 * every tenant rule mutation) and invalidates the local
 * {@link TenantRuleLoader} cache for the affected tenant. The next evaluation
 * for that tenant rebuilds its KieContainer from the current database state.
 *
 * <p>Lives in {@code rules-engine} so any consuming service (claims,
 * contributions, finance, user, even rules-service itself) gets the
 * invalidator for free as long as it (a) has a {@code ReceiverOptions} bean
 * registered (each service defines its own per-service consumer group via
 * its own {@code KafkaConsumerConfig}), and (b) includes
 * {@code com.medfund.rules.consumer} in its component scan.
 *
 * <p>{@code @ConditionalOnBean(ReceiverOptions.class)} guards services that
 * don't have a Kafka receiver configured (some test contexts) from failing
 * application bootstrap.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnBean(ReceiverOptions.class)
public class RuleChangeConsumer {

    private static final String TOPIC = "medfund.rules.updated";

    private final ReceiverOptions<String, String> receiverOptions;
    private final TenantRuleLoader loader;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
            .receive()
            .flatMap(record -> processEvent(record.value())
                    .doOnSuccess(v -> record.receiverOffset().acknowledge())
                    .onErrorResume(e -> {
                        log.error("[rule-change] Failed to process record offset={}: {}",
                                record.receiverOffset().offset(), e.getMessage());
                        record.receiverOffset().acknowledge();
                        return Mono.empty();
                    }))
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                    .maxBackoff(Duration.ofMinutes(2))
                    .doBeforeRetry(sig -> log.warn("[rule-change] Consumer restarting after error: {}",
                            sig.failure().getMessage())))
            .subscribe();
    }

    private Mono<Void> processEvent(String json) {
        if (json == null || json.isBlank()) return Mono.empty();
        try {
            JsonNode node = objectMapper.readTree(json);
            String tenantStr = node.path("tenantId").asText("");
            if (tenantStr.isBlank()) return Mono.empty();
            UUID tenantId = UUID.fromString(tenantStr);
            log.debug("[rule-change] Invalidating tenant {} cache (action={})",
                    tenantId, node.path("action").asText("?"));
            loader.invalidate(tenantId);
            return Mono.empty();
        } catch (Exception e) {
            log.warn("[rule-change] Could not parse event payload: {}", e.getMessage());
            return Mono.empty();
        }
    }
}
