package com.medfund.shared.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Listens on {@code medfund.permissions.invalidated} and tells the local
 * {@link PermissionResolver} to drop cached entries. Every service that
 * depends on {@code shared} and has Kafka consumer config gets one of these
 * — independent consumer groups (one per service) ensure broadcast delivery.
 *
 * <p>Payload shape:
 * <pre>{@code
 * { "event": "PERMISSIONS_INVALIDATED", "tenantId": "...", "userId": "..." }
 * }</pre>
 * {@code userId} is optional — when missing, every cached entry for the tenant
 * is dropped (used after role-permission edits that affect every user holding
 * the role).
 *
 * <p>Errors here are intentionally swallowed after logging — a poisoned
 * invalidation event must not stall the consumer or leak stale permissions
 * indefinitely. Worst case a missed invalidation expires after the 60-s TTL.
 */
@Slf4j
@RequiredArgsConstructor
public class PermissionInvalidationConsumer {

    private static final String TOPIC = "medfund.permissions.invalidated";

    private final ReceiverOptions<String, String> receiverOptions;
    private final PermissionResolver resolver;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(e -> {
                            log.error("[permissions-invalidated] processing failed offset={}: {}",
                                    record.receiverOffset().offset(), e.getMessage());
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                        .maxBackoff(Duration.ofMinutes(2))
                        .doBeforeRetry(sig -> log.warn(
                                "[permissions-invalidated] consumer restarting after error: {}",
                                sig.failure().getMessage())))
                .subscribe();
        log.info("[permissions-invalidated] consumer started");
    }

    private Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String tenantId = node.path("tenantId").asText(null);
            if (tenantId == null || tenantId.isBlank()) {
                log.warn("[permissions-invalidated] missing tenantId — skipping");
                return Mono.empty();
            }
            String userIdText = node.path("userId").asText(null);
            if (userIdText == null || userIdText.isBlank()) {
                resolver.invalidateTenant(tenantId);
                log.debug("[permissions-invalidated] flushed tenant {}", tenantId);
            } else {
                try {
                    resolver.invalidate(tenantId, UUID.fromString(userIdText));
                    log.debug("[permissions-invalidated] flushed tenant={} user={}", tenantId, userIdText);
                } catch (IllegalArgumentException e) {
                    log.warn("[permissions-invalidated] bad userId '{}' — skipping", userIdText);
                }
            }
            return Mono.empty();
        } catch (Exception e) {
            log.error("[permissions-invalidated] payload parse failed: {}", e.getMessage());
            return Mono.error(e);
        }
    }
}
