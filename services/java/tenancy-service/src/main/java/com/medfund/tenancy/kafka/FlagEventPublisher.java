package com.medfund.tenancy.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Broadcasts platform feature-flag toggles so every service can drop its
 * cached value immediately instead of waiting out the 30-second TTL in
 * {@code FlagRegistryR2dbc}. Consumed by {@code FlagInvalidationConsumer} in
 * the shared module (Java) and {@code shared/flags.Registry} (Go).
 *
 * <p>Topic contract: {@code platform.feature-flags.v1} is additive-only.
 * Fields may be added; removing or renaming one requires a {@code v2} topic.
 *
 * <p>Publish failures are logged and swallowed, and the attempt is bounded by
 * {@link #PUBLISH_TIMEOUT}. A flag toggle that reaches Postgres but not Kafka
 * still propagates within the registry's 30-second TTL, so failing (or
 * stalling) the admin's request over an unreachable broker would be a strictly
 * worse outcome than a slower rollout. Without the timeout the request would
 * block on the producer's 60-second {@code max.block.ms} default.
 */
@Slf4j
@Component
public class FlagEventPublisher {

    static final String TOPIC = "platform.feature-flags.v1";

    /**
     * Upper bound on how long a flag toggle waits for the broker. Short
     * enough that an admin never sees a hung Save, long enough to ride out a
     * leader election on a healthy cluster.
     */
    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(2);

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public FlagEventPublisher(KafkaSender<String, String> kafkaSender, ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> publish(String key, boolean enabled, String actorEmail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("key", key);
        payload.put("enabled", enabled);
        payload.put("updatedAt", OffsetDateTime.now().toString());
        payload.put("actor", actorEmail == null ? "" : actorEmail);

        try {
            String value = objectMapper.writeValueAsString(payload);
            // Keyed by flag so all events for one flag share a partition and
            // therefore arrive in order.
            var record = new ProducerRecord<>(TOPIC, key, value);
            return kafkaSender.send(Mono.just(SenderRecord.create(record, key)))
                    .then()
                    .timeout(PUBLISH_TIMEOUT)
                    .doOnError(e -> log.error(
                            "Failed to broadcast flag change for {}; consumers will pick it up "
                                    + "on their next cache expiry", key, e))
                    .onErrorResume(e -> Mono.empty());
        } catch (Exception e) {
            log.error("Failed to serialise flag change for {}", key, e);
            return Mono.empty();
        }
    }
}
