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
import java.util.UUID;

/**
 * Subscribes to {@code medfund.user.policy-status-changed} (Phase 13 §B
 * per L6) and steers the {@link EarningScheduleClosureService} state
 * machine per grill note 5:
 * <ul>
 *   <li>{@code lapsed} / {@code terminated} — close every open future
 *       period at {@code earned_at_period_end = 0}.</li>
 *   <li>{@code suspended} — freeze every open future period; the row
 *       stays uncounted until unwound.</li>
 *   <li>{@code active} coming from {@code suspended} — resume the
 *       frozen periods so the nightly executor picks them up.</li>
 *   <li>{@code active} coming from {@code lapsed} / {@code terminated}
 *       — reinstate the closed periods (pro-rata = 1.0 of the original
 *       written_amount per grill note 5).</li>
 * </ul>
 *
 * <p>Offset ack matches {@link PolicyEndorsedConsumer}: on success only
 * per {@code bug_reactor_kafka_ack_swallow}, with error-path ack to keep
 * a poison record from blocking the partition. The consumer logs the
 * full cause chain on failure so an operator can trace the exact reason.
 *
 * <p>Idempotency: every close / freeze event mints a fresh
 * {@code closureRef} UUID that fans out across every affected period.
 * The service's pre-write COUNT lookup on {@code closure_ref} guarantees
 * a redelivered event is a no-op.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyStatusChangedConsumer {

    static final String TOPIC = "medfund.user.policy-status-changed";

    private final ReceiverOptions<String, String> receiverOptions;
    private final PolicyStatusChangedPayloadParser parser;
    private final EarningScheduleClosureService closureService;

    @PostConstruct
    public void consume() {
        KafkaReceiver.create(receiverOptions.subscription(Collections.singleton(TOPIC)))
                .receive()
                .flatMap(record -> processRecord(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(err -> {
                            log.error("policy-status-changed consumer failed for record — full chain: {}. "
                                            + "Ack anyway to unblock partition.",
                                    PolicyIssuedConsumer.chainMessages(err), err);
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("policy-status-changed consumer stream error: {}", e.getMessage()))
                .retry()
                .subscribe();
    }

    Mono<Void> processRecord(String json) {
        return Mono.defer(() -> {
            PolicyStatusChangedPayload payload;
            try {
                payload = parser.parse(json);
            } catch (Exception e) {
                log.error("policy-status-changed payload unparseable — full chain: {}",
                        PolicyIssuedConsumer.chainMessages(e), e);
                return Mono.empty();
            }
            String tenantId = payload.tenantId();
            if (tenantId == null || tenantId.isBlank()) {
                log.error("policy-status-changed payload missing tenantId — cannot scope closure, dropping.");
                return Mono.empty();
            }
            if (payload.policyId() == null || payload.policySource() == null
                    || payload.toStatus() == null || payload.effectiveAt() == null) {
                log.error("policy-status-changed payload missing required fields "
                                + "(policyId/policySource/toStatus/effectiveAt) - dropping.");
                return Mono.empty();
            }
            return dispatch(payload)
                    .contextWrite(Context.of(TenantContext.KEY, tenantId));
        });
    }

    Mono<Void> dispatch(PolicyStatusChangedPayload payload) {
        String tenantId = payload.tenantId();
        UUID policyId = payload.policyId();
        String source = payload.policySource();
        java.time.LocalDate effectiveDate = payload.effectiveAt().toLocalDate();
        String from = payload.fromStatus();
        String to = payload.toStatus();

        if ("lapsed".equals(to) || "terminated".equals(to)) {
            return closureService.closeOutForPolicyClosure(tenantId, policyId, source, effectiveDate,
                            deterministicRef(policyId, to, effectiveDate))
                    .then();
        }
        if ("suspended".equals(to)) {
            return closureService.freezePolicyEarning(tenantId, policyId, source, effectiveDate,
                            deterministicRef(policyId, to, effectiveDate))
                    .then();
        }
        if ("active".equals(to)) {
            if ("suspended".equals(from)) {
                return closureService.resumePolicyEarning(tenantId, policyId, source, effectiveDate).then();
            }
            if ("lapsed".equals(from) || "terminated".equals(from)) {
                return closureService.reinstatePolicyEarning(tenantId, policyId, source, effectiveDate).then();
            }
        }
        log.debug("policy-status-changed: no earning-schedule action for {}→{} on policy {}",
                from, to, policyId);
        return Mono.empty();
    }

    /**
     * Redelivery-stable closure_ref: same policy + same transition +
     * same effectiveDate → same UUID. Pre-write COUNT lookup in the
     * service therefore drops the second copy of a redelivered event
     * even though the Kafka record itself is byte-identical.
     */
    private static UUID deterministicRef(UUID policyId, String toStatus, java.time.LocalDate effectiveDate) {
        String seed = policyId + "|" + toStatus + "|" + effectiveDate;
        return UUID.nameUUIDFromBytes(seed.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
