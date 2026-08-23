package com.medfund.contributions.premium.consumer;

import com.medfund.contributions.premium.service.EarningScheduleClosureService;
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

/**
 * Subscribes to {@code medfund.user.policy-endorsed} (Phase 12 §C Phase 9)
 * — user-service publishes on endorsement commit. For every event the
 * {@link EarningScheduleClosureService#recomputeForEndorsement} pass
 * rewrites the earning strip for periods on or after
 * {@code effectiveFrom}, apportioning the {@code premiumDelta} in the
 * same days-in-period proportion the base rows used.
 *
 * <p>Offset ack matches {@link PolicyIssuedConsumer}: on success only per
 * {@code bug_reactor_kafka_ack_swallow}, with error-path ack to keep a
 * poison message from blocking the partition.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyEndorsedConsumer {

    static final String TOPIC = "medfund.user.policy-endorsed";

    private final ReceiverOptions<String, String> receiverOptions;
    private final PolicyEndorsedPayloadParser parser;
    private final EarningScheduleClosureService closureService;

    @PostConstruct
    public void consume() {
        KafkaReceiver.create(receiverOptions.subscription(Collections.singleton(TOPIC)))
                .receive()
                .flatMap(record -> processRecord(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(err -> {
                            log.error("policy-endorsed consumer failed for record — full chain: {}. "
                                            + "Ack anyway to unblock partition.",
                                    PolicyIssuedConsumer.chainMessages(err), err);
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("policy-endorsed consumer stream error: {}", e.getMessage()))
                .retry()
                .subscribe();
    }

    Mono<Void> processRecord(String json) {
        return Mono.defer(() -> {
            PolicyEndorsedPayload payload;
            try {
                payload = parser.parse(json);
            } catch (Exception e) {
                log.error("policy-endorsed payload unparseable — full chain: {}",
                        PolicyIssuedConsumer.chainMessages(e), e);
                return Mono.empty();
            }
            String tenantId = payload.tenantId();
            if (tenantId == null || tenantId.isBlank()) {
                log.error("policy-endorsed payload missing tenantId — cannot scope recompute, dropping.");
                return Mono.empty();
            }
            if (payload.endorsementId() == null || payload.policyId() == null
                    || payload.policySource() == null || payload.effectiveFrom() == null) {
                log.error("policy-endorsed payload missing required fields (endorsementId/policyId/"
                                + "policySource/effectiveFrom) — dropping. reference={}",
                        payload.reference());
                return Mono.empty();
            }
            return closureService.recomputeForEndorsement(tenantId,
                            payload.endorsementId(),
                            payload.policyId(),
                            payload.policySource(),
                            payload.effectiveFrom(),
                            payload.premiumDelta(),
                            payload.currencyCode())
                    .then()
                    .contextWrite(Context.of(TenantContext.KEY, tenantId));
        });
    }
}
