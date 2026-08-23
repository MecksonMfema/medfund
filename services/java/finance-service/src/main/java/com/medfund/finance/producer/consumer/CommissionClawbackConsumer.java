package com.medfund.finance.producer.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.producer.dto.MemberLifecycleEvent;
import com.medfund.finance.producer.service.CommissionClawbackService;
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
 * Kafka consumer on {@code medfund.users.member-lifecycle} that fires a
 * commission clawback when a member transitions to a terminal status
 * ({@code lapsed} / {@code terminated} / {@code deactivated}). Non-terminal
 * transitions ({@code activated}, {@code suspended}, {@code reinstated})
 * are silent no-ops — commissions are only clawed back when the
 * relationship truly ends.
 *
 * <p>§A of the plan handles operator-triggered lifecycle events. §B (Phase 7)
 * bolts on the auto-lapse chain: the {@code ScheduledStatusExecutor} job in
 * user-service transitions the member and emits the same
 * {@code MEMBER_STATUS_CHANGED} — this consumer picks it up unmodified.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CommissionClawbackConsumer {

    private static final String TOPIC = "medfund.users.member-lifecycle";
    private static final Set<String> TERMINAL_STATUSES =
            Set.of("lapsed", "terminated", "deactivated");

    private final ReceiverOptions<String, String> receiverOptions;
    private final CommissionClawbackService commissionClawbackService;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> {
                    try {
                        return processEvent(record.value())
                                .doOnSuccess(v -> record.receiverOffset().acknowledge())
                                .doOnError(e -> log.error(
                                        "Failed to process member-lifecycle event for commission clawback (full chain): ",
                                        e))
                                .onErrorResume(e -> {
                                    record.receiverOffset().acknowledge();
                                    return Mono.empty();
                                });
                    } catch (Exception e) {
                        log.error("Error deserializing member-lifecycle event for commission clawback: ", e);
                        record.receiverOffset().acknowledge();
                        return Mono.empty();
                    }
                })
                .doOnError(e -> log.error("Commission clawback consumer error: ", e))
                .retry()
                .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String eventType = node.path("event").asText();
            if (!"MEMBER_STATUS_CHANGED".equals(eventType)) {
                log.debug("Skipping commission clawback — unexpected event type '{}'", eventType);
                return Mono.empty();
            }
            MemberLifecycleEvent event = MemberLifecycleEvent.from(node);
            if (event.memberId() == null || event.tenantId() == null
                    || event.tenantId().isBlank()) {
                log.debug("Skipping commission clawback — missing member or tenant id");
                return Mono.empty();
            }
            String status = event.status() != null ? event.status().toLowerCase() : "";
            if (!TERMINAL_STATUSES.contains(status)) {
                log.debug("Skipping commission clawback — status '{}' is not terminal", status);
                return Mono.empty();
            }
            String[] systemActor = AuditActor.systemActor();
            return commissionClawbackService.processMemberLapse(
                            event.memberId(),
                            event.eventInstant(),
                            "MEMBER_" + status.toUpperCase() + (event.reason() == null || event.reason().isBlank()
                                    ? "" : ": " + event.reason()),
                            systemActor[0], systemActor[1])
                    .then()
                    .contextWrite(Context.of(TenantContext.KEY, event.tenantId()));
        } catch (Exception e) {
            log.error("Failed to parse member-lifecycle event for commission clawback: ", e);
            return Mono.error(e);
        }
    }
}
