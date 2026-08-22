package com.medfund.finance.reinsurance.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.reinsurance.dto.ClaimAdjudicatedEvent;
import com.medfund.finance.reinsurance.service.CessionService;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.context.Context;

import java.util.Collections;
import java.util.Set;

/**
 * Sibling to {@link com.medfund.finance.consumer.ClaimAdjudicatedConsumer} —
 * a second subscriber on {@code medfund.claims.adjudicated} inside
 * finance-service, in its own consumer group so it acks independently of
 * the balance/payable path. Delegates to {@link CessionService} for the
 * REINSURANCE agenda-group dispatch.
 *
 * <p>Ack via {@code .doOnSuccess} — never {@code .doOnTerminate} — per
 * {@code bug_reactor_kafka_ack_swallow}: a failed cession write must NOT
 * ack, so Kafka at-least-once retries can retry that specific record.
 * Errors log the full cause chain and swallow the message downstream
 * (through {@code .onErrorResume}) to avoid poison-pill retry loops on
 * genuinely malformed records — the write itself already succeeded or
 * failed inside its own transaction.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReinsuranceLossCessionConsumer {

    private static final String TOPIC = "medfund.claims.adjudicated";
    private static final Set<String> CEDING_DECISIONS = Set.of("APPROVED", "PARTIAL_APPROVED");

    private final ReceiverOptions<String, String> receiverOptions;
    private final CessionService cessionService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        // Explicit consumer group id so this subscriber's offset is independent
        // of the balance/payable consumer already on this topic. Falls back to
        // the auto-configured group.id when the caller has not overridden.
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> {
                    try {
                        return processEvent(record.value())
                                .doOnSuccess(v -> record.receiverOffset().acknowledge())
                                .doOnError(e -> log.error(
                                        "Failed to process claim-adjudicated event for reinsurance (full chain): ",
                                        e))
                                .onErrorResume(e -> Mono.empty());
                    } catch (Exception e) {
                        log.error("Error deserializing claim-adjudicated event for reinsurance: ", e);
                        record.receiverOffset().acknowledge();
                        return Mono.empty();
                    }
                })
                .doOnError(e -> log.error("Reinsurance loss cession consumer error: ", e))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String decision = text(node, "decision");
            if (decision == null || !CEDING_DECISIONS.contains(decision.toUpperCase())) {
                return Mono.empty();
            }

            ClaimAdjudicatedEvent event = ClaimAdjudicatedEvent.from(node);
            if (event.claimId() == null || event.insuranceLine() == null
                    || event.tenantId() == null || event.tenantId().isBlank()) {
                log.debug("Skipping reinsurance-side claim event — missing claimId/line/tenant");
                return Mono.empty();
            }

            String[] systemActor = AuditActor.systemActor();
            return cessionService.processAdjudicatedClaim(event, systemActor[0], systemActor[1])
                    .then()
                    .contextWrite(Context.of(TenantContext.KEY, event.tenantId()));
        } catch (Exception e) {
            log.error("Failed to parse claim-adjudicated event for reinsurance: ", e);
            return Mono.error(e);
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return (v == null || v.isBlank() || "null".equals(v)) ? null : v;
    }
}
