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
 * Broadcasts per-tenant module-entitlement toggles so every service can drop
 * its cached value immediately instead of waiting out the 30-second TTL in
 * {@code TenantEntitlementRegistryR2dbc}. Consumed by
 * {@code EntitlementInvalidationConsumer} in the shared module (Java) and by
 * {@code shared/entitlements.Registry} (Go, the gateway gate).
 *
 * <p>Topic contract: {@code platform.module-entitlements.v1} is additive-only.
 * Fields may be added; removing or renaming one requires a {@code v2} topic.
 *
 * <p>Publish failures are logged and swallowed, and the attempt is bounded by
 * {@link #PUBLISH_TIMEOUT} — a toggle that reaches Postgres but not Kafka still
 * propagates within the registry's 30-second TTL, so failing the admin's
 * request over an unreachable broker would be strictly worse than a slower
 * rollout. Mirrors {@code FlagEventPublisher}.
 */
@Slf4j
@Component
public class ModuleEntitlementEventPublisher {

    static final String TOPIC = "platform.module-entitlements.v1";

    static final Duration PUBLISH_TIMEOUT = Duration.ofSeconds(2);

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public ModuleEntitlementEventPublisher(KafkaSender<String, String> kafkaSender, ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> publish(String tenantId, String moduleKey, boolean enabled, String actorEmail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenantId);
        payload.put("moduleKey", moduleKey);
        payload.put("enabled", enabled);
        payload.put("updatedAt", OffsetDateTime.now().toString());
        payload.put("actor", actorEmail == null ? "" : actorEmail);

        try {
            String value = objectMapper.writeValueAsString(payload);
            // Keyed by tenant+module so all events for one (tenant, module)
            // share a partition and therefore arrive in order.
            String key = tenantId + "|" + moduleKey;
            var record = new ProducerRecord<>(TOPIC, key, value);
            return kafkaSender.send(Mono.just(SenderRecord.create(record, key)))
                    .then()
                    .timeout(PUBLISH_TIMEOUT)
                    .doOnError(e -> log.error(
                            "Failed to broadcast entitlement change for {}/{}; consumers will pick it up "
                                    + "on their next cache expiry", tenantId, moduleKey, e))
                    .onErrorResume(e -> Mono.empty());
        } catch (Exception e) {
            log.error("Failed to serialise entitlement change for {}/{}", tenantId, moduleKey, e);
            return Mono.empty();
        }
    }
}
