package com.medfund.user.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.user.service.RoleService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;

@Slf4j
@Component
@RequiredArgsConstructor
public class TenantProvisionedConsumer {

    private static final String TOPIC = "medfund.tenants.provisioned";

    private final ReceiverOptions<String, String> receiverOptions;
    private final RoleService roleService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
            .receive()
            .flatMap(record -> {
                // Per-record errors are swallowed here — they must never escape to
                // the outer stream, otherwise .retryWhen() would re-subscribe from
                // the earliest offset and replay all historical messages in a tight loop.
                return processEvent(record.value())
                    .doOnSuccess(v -> record.receiverOffset().acknowledge())
                    .onErrorResume(e -> {
                        log.error("[tenant-provisioned] Failed to process record offset={}: {}",
                            record.receiverOffset().offset(), e.getMessage());
                        // Acknowledge the bad record so we don't get stuck on it
                        record.receiverOffset().acknowledge();
                        return Mono.empty();
                    });
            })
            // Outer retryWhen only fires on Kafka infrastructure errors (connection lost, etc.)
            // Exponential backoff prevents CPU-spinning on repeated failures.
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                .maxBackoff(Duration.ofMinutes(2))
                .doBeforeRetry(sig -> log.warn("[tenant-provisioned] Consumer restarting after error: {}",
                    sig.failure().getMessage())))
            .subscribe();
    }

    // Package-private so the unit test in the matching package can drive the
    // event-handling logic without spinning up the full Kafka receiver.
    Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String tenantId = node.get("tenantId").asText();
            String slug     = node.get("slug").asText();
            log.info("[tenant-provisioned] Seeding default roles for tenant={} slug={}", tenantId, slug);
            return roleService.seedDefaultRoles(tenantId).then();
        } catch (Exception e) {
            log.error("[tenant-provisioned] Failed to parse event payload: {}", e.getMessage());
            return Mono.error(e);
        }
    }
}
