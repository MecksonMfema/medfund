package com.medfund.contributions.premium.consumer;

import com.medfund.contributions.client.Ifrs17CohortClient;
import com.medfund.contributions.premium.service.EarningScheduleService;
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
 * Subscribes to {@code medfund.user.policy-issued} (Phase 12 §A). For every
 * event the {@link EarningScheduleService} projects one or more
 * {@code earning_schedule} rows for the annual-bind policy.
 *
 * <p>Offset ack is on success only (per {@code bug_reactor_kafka_ack_swallow})
 * — a downstream failure must not silently drop the record. On error the
 * full cause chain is logged; the offset is still acked so a poison
 * message does not block the partition, matching the pattern of the other
 * consumers in this service (see {@code MemberEnrolledConsumer}).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PolicyIssuedConsumer {

    static final String TOPIC = "medfund.user.policy-issued";

    private final ReceiverOptions<String, String> receiverOptions;
    private final PolicyIssuedPayloadParser parser;
    private final EarningScheduleService earningScheduleService;
    private final Ifrs17CohortClient ifrs17CohortClient;

    @PostConstruct
    public void consume() {
        KafkaReceiver.create(receiverOptions.subscription(Collections.singleton(TOPIC)))
                .receive()
                .flatMap(record -> processRecord(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .onErrorResume(err -> {
                            log.error("policy-issued consumer failed for record — full chain: {}. Ack anyway to unblock partition.",
                                    chainMessages(err), err);
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("policy-issued consumer stream error: {}", e.getMessage()))
                .retry()
                .subscribe();
    }

    Mono<Void> processRecord(String json) {
        return Mono.defer(() -> {
            PolicyIssuedPayload payload;
            try {
                payload = parser.parse(json);
            } catch (Exception e) {
                log.error("policy-issued payload unparseable — full chain: {}", chainMessages(e), e);
                return Mono.empty();
            }
            String tenantId = payload.tenantId();
            if (tenantId == null || tenantId.isBlank()) {
                log.error("policy-issued payload missing tenantId — cannot scope earning-schedule write, dropping.");
                return Mono.empty();
            }
            return earningScheduleService.writeSchedule(payload)
                    .then(maybeLockInYieldCurve(payload))
                    .contextWrite(Context.of(TenantContext.KEY, tenantId));
        });
    }

    /**
     * Phase 15 §6 (I18): fire-and-forget lock-in call so the cohort captures
     * its discount curve at initial recognition. The user-service side is
     * idempotent — repeat calls after the first are cheap no-ops. Failures
     * are swallowed by {@link Ifrs17CohortClient} so a user-service outage
     * cannot block premium earning.
     */
    private Mono<Void> maybeLockInYieldCurve(PolicyIssuedPayload payload) {
        if (payload.cohortId() == null || payload.currencyCode() == null || payload.coverageStart() == null) {
            return Mono.empty();
        }
        return ifrs17CohortClient.lockInIfFirstPolicy(
                payload.cohortId(),
                payload.currencyCode(),
                payload.coverageStart());
    }

    static String chainMessages(Throwable t) {
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
        Throwable c = t.getCause();
        while (c != null) {
            sb.append(" ← ").append(c.getClass().getSimpleName()).append(": ").append(c.getMessage());
            c = c.getCause();
        }
        return sb.toString();
    }
}
