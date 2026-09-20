package com.medfund.shared.flags;

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
 * Listens on {@code platform.feature-flags.v1} and drops the locally cached
 * value for any flag a super admin toggles, so the change lands in this
 * service within a round trip instead of waiting out the registry's 30-second
 * TTL. Every service that depends on {@code shared} and has Kafka consumer
 * config gets one — independent consumer groups (one per service) make this a
 * broadcast, exactly as {@code PermissionInvalidationConsumer} does for
 * permissions.
 *
 * <p>Payload shape (additive-only contract; fields may be added, never
 * removed or renamed without a {@code v2} topic):
 * <pre>{@code
 * { "key": "AI_ADJUDICATION", "enabled": true,
 *   "updatedAt": "2026-09-20T12:00:00Z", "actor": "admin@medfund.com" }
 * }</pre>
 *
 * <p>This consumer deliberately invalidates rather than caching
 * {@code enabled} straight off the event. The DB row is the single source of
 * truth; trusting the payload would let an out-of-order delivery pin a stale
 * value until the next toggle, whereas an invalidation is idempotent and
 * self-correcting.
 *
 * <p>Errors are logged with the full cause chain and then acknowledged — a
 * poisoned event must not stall the consumer, because a stalled consumer
 * silently degrades every downstream service back to TTL-only propagation.
 */
@Slf4j
@RequiredArgsConstructor
public class FlagInvalidationConsumer {

    static final String TOPIC = "platform.feature-flags.v1";

    private final ReceiverOptions<String, String> receiverOptions;
    private final FlagRegistry registry;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                // Ack on doOnSuccess, never doOnTerminate: doOnTerminate also
                // fires on error and would silently drop a failed record
                // (bug_reactor_kafka_ack_swallow).
                .flatMap(record -> processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(e -> {
                            log.error("[feature-flags] processing failed offset={}",
                                    record.receiverOffset().offset(), e);
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                        .maxBackoff(Duration.ofMinutes(2))
                        .doBeforeRetry(sig -> log.warn(
                                "[feature-flags] consumer restarting after error: {}",
                                sig.failure().getMessage())))
                .subscribe();
        log.info("[feature-flags] invalidation consumer started on {}", TOPIC);
    }

    private Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String key = node.path("key").asText(null);
            if (key == null || key.isBlank()) {
                log.warn("[feature-flags] event without a key — skipping");
                return Mono.empty();
            }
            registry.invalidate(key);
            log.info("[feature-flags] {} changed (enabled={}) — cache dropped",
                    key, node.path("enabled").asBoolean());
            return Mono.empty();
        } catch (Exception e) {
            log.error("[feature-flags] payload parse failed", e);
            return Mono.error(e);
        }
    }
}
