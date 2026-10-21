package com.medfund.finance.reinsurance.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.reinsurance.dto.ClaimAdjudicatedEvent;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.service.CessionService;
import com.medfund.finance.reinsurance.service.ReinsuranceReviewTaskService;
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

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
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
    /**
     * Phase 8: decisions that can *terminate* a prior cession's basis. A
     * REJECTED lands with {@code approvedAmount = 0} which is trivially
     * less than any prior basis; PARTIAL_APPROVED / APPROVED are checked
     * numerically. Anything else (PENDING, RETURNED, …) is ignored.
     */
    private static final Set<String> REGRESSION_CANDIDATE_DECISIONS = Set.of(
            "APPROVED", "PARTIAL_APPROVED", "REJECTED");

    private final ReceiverOptions<String, String> receiverOptions;
    private final CessionService cessionService;
    private final CessionRepository cessionRepository;
    private final ReinsuranceReviewTaskService reviewTaskService;
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
            String rawDecision = text(node, "decision");
            String decision = rawDecision != null ? rawDecision.toUpperCase() : null;
            if (decision == null || !REGRESSION_CANDIDATE_DECISIONS.contains(decision)) {
                return Mono.empty();
            }

            ClaimAdjudicatedEvent event = ClaimAdjudicatedEvent.from(node);
            if (event.claimId() == null || event.insuranceLine() == null
                    || event.tenantId() == null || event.tenantId().isBlank()) {
                log.debug("Skipping reinsurance-side claim event — missing claimId/line/tenant");
                return Mono.empty();
            }

            String[] systemActor = AuditActor.systemActor();
            String finalDecision = decision;
            return dispatchByHistory(event, finalDecision, systemActor)
                    .contextWrite(Context.of(TenantContext.KEY, event.tenantId()));
        } catch (Exception e) {
            log.error("Failed to parse claim-adjudicated event for reinsurance: ", e);
            return Mono.error(e);
        }
    }

    /**
     * Phase 8 regression path: look up existing ACTIVE LOSS cessions for
     * this claim id. If none, treat as a first-time cede (unchanged
     * Phase 3 behaviour — the REJECTED short-circuit still applies since
     * a REJECTED with no history has nothing to regress against). If
     * some exist, only open a review task when the new basis is strictly
     * lower than *any* prior basis; a re-adjudication that leaves things
     * equal or increases is a no-op (rules-engine idempotency handles
     * the re-cede case at the write layer via UNIQUE).
     */
    private Mono<Void> dispatchByHistory(ClaimAdjudicatedEvent event, String decision,
                                         String[] systemActor) {
        return cessionRepository.findBySourceEventId(event.claimId())
                .filter(c -> "LOSS".equals(c.getCessionType())
                        && "ACTIVE".equals(c.getStatus()))
                .collectList()
                .flatMap(existing -> {
                    if (existing.isEmpty()) {
                        if ("REJECTED".equals(decision)) {
                            log.debug("Claim {} REJECTED with no prior cession — nothing to do",
                                    event.claimId());
                            return Mono.empty();
                        }
                        return cessionService
                                .processAdjudicatedClaim(event, systemActor[0], systemActor[1])
                                .then();
                    }
                    BigDecimal newBasis = "REJECTED".equals(decision)
                            ? BigDecimal.ZERO
                            : (event.approvedAmount() != null
                                    ? event.approvedAmount() : BigDecimal.ZERO);
                    boolean regression = existing.stream()
                            .anyMatch(c -> newBasis.compareTo(c.getBasisAmount()) < 0);
                    if (!regression) {
                        log.debug("Claim {} re-adjudicated at same-or-higher basis — no cession change",
                                event.claimId());
                        return Mono.empty();
                    }
                    return openRegressionTasks(event.claimId(), existing, newBasis, systemActor);
                });
    }

    private Mono<Void> openRegressionTasks(java.util.UUID claimId, List<Cession> priorCessions,
                                           BigDecimal newBasis, String[] systemActor) {
        return reviewTaskService.createRegressionTasks(claimId, priorCessions, newBasis,
                        systemActor[0], systemActor[1])
                .then();
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return (v == null || v.isBlank() || "null".equals(v)) ? null : v;
    }
}
