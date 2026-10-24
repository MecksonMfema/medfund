package com.medfund.shared.entitlement;

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

/**
 * Listens on {@code platform.module-entitlements.v1} and drops the locally
 * cached value for any {@code (tenant, module)} a super admin toggles, so the
 * change lands in this service within a round trip instead of waiting out the
 * registry's 30-second TTL. Every service that depends on {@code shared} and
 * has Kafka consumer config gets one — independent consumer groups (one per
 * service) make this a broadcast, exactly as {@code FlagInvalidationConsumer}
 * does for flags.
 *
 * <p>Payload shape (additive-only contract; fields may be added, never removed
 * or renamed without a {@code v2} topic):
 * <pre>{@code
 * { "tenantId": "…uuid…", "moduleKey": "KYC", "enabled": true,
 *   "updatedAt": "2026-10-23T12:00:00Z", "actor": "admin@medfund.com" }
 * }</pre>
 *
 * <p>Invalidates rather than caching {@code enabled} straight off the event:
 * the DB row is the single source of truth, so an out-of-order delivery can
 * never pin a stale value — an invalidation is idempotent and self-correcting.
 *
 * <p>Errors are logged with the full cause chain and then acknowledged — a
 * poisoned event must not stall the consumer, which would silently degrade the
 * gate back to TTL-only propagation (see bug_reactor_kafka_ack_swallow: ack on
 * {@code doOnSuccess}, never {@code doOnTerminate}).
 */
@Slf4j
@RequiredArgsConstructor
public class EntitlementInvalidationConsumer {

    static final String TOPIC = "platform.module-entitlements.v1";

    private final ReceiverOptions<String, String> receiverOptions;
    private final TenantEntitlementRegistry registry;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(e -> {
                            log.error("[module-entitlements] processing failed offset={}",
                                    record.receiverOffset().offset(), e);
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                        .maxBackoff(Duration.ofMinutes(2))
                        .doBeforeRetry(sig -> log.warn(
                                "[module-entitlements] consumer restarting after error: {}",
                                sig.failure().getMessage())))
                .subscribe();
        log.info("[module-entitlements] invalidation consumer started on {}", TOPIC);
    }

    private Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String tenantId = node.path("tenantId").asText(null);
            String moduleKey = node.path("moduleKey").asText(null);
            if (tenantId == null || tenantId.isBlank() || moduleKey == null || moduleKey.isBlank()) {
                log.warn("[module-entitlements] event missing tenantId/moduleKey — skipping");
                return Mono.empty();
            }
            registry.invalidate(tenantId, moduleKey);
            log.info("[module-entitlements] tenant={} module={} changed (enabled={}) — cache dropped",
                    tenantId, moduleKey, node.path("enabled").asBoolean());
            return Mono.empty();
        } catch (Exception e) {
            log.error("[module-entitlements] payload parse failed", e);
            return Mono.error(e);
        }
    }
}
