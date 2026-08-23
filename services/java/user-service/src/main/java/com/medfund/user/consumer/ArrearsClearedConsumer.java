package com.medfund.user.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.service.MemberService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.context.Context;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;

/**
 * Consumes {@code medfund.contributions.arrears-cleared} (published by
 * {@code BillingService.recordPayment} when a payment drops the member
 * back into GRACE from SUSPENDED/WRITE_OFF). Cancels a pending
 * scheduled LAPSED transition on the member if the effective date
 * hasn't passed yet — the SCHEDULED_STATUS_ROLL job otherwise flips
 * the member to LAPSED even though they've paid up.
 *
 * <p>No-op when the member has no scheduled status or when the
 * scheduled status is not {@code "lapsed"} (an operator scheduled
 * something else — this consumer must not touch operator-scheduled
 * lifecycle events).
 *
 * <p>Per {@code bug_reactor_kafka_ack_swallow}: offset ack uses
 * {@code .doOnSuccess} exclusively.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArrearsClearedConsumer {

    private static final String TOPIC = "medfund.contributions.arrears-cleared";
    private static final String CANCEL_REASON = "ARREARS_CLEARED";

    private final ReceiverOptions<String, String> receiverOptions;
    private final ObjectMapper objectMapper;
    private final MemberService memberService;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
            .receive()
            .flatMap(record -> processEvent(record.value())
                .doOnSuccess(v -> record.receiverOffset().acknowledge())
                .onErrorResume(e -> {
                    log.error("[{}] Failed to process record offset={}: {}",
                            TOPIC, record.receiverOffset().offset(), fullCauseChain(e));
                    record.receiverOffset().acknowledge();
                    return Mono.empty();
                }))
            .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(5))
                    .maxBackoff(Duration.ofMinutes(2))
                    .doBeforeRetry(sig -> log.warn("[{}] Consumer restarting after error: {}",
                            TOPIC, sig.failure().getMessage())))
            .subscribe();
    }

    Mono<Void> processEvent(String json) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("[{}] Malformed JSON dropped: {}", TOPIC, e.getMessage());
            return Mono.empty();
        }
        if (!"ARREARS_CLEARED".equals(node.path("event").asText())) return Mono.empty();
        if (!"MEMBER".equals(node.path("subjectType").asText())) return Mono.empty();

        String memberIdStr = node.path("subjectId").asText();
        String tenantIdStr = node.path("tenantId").asText();
        if (memberIdStr == null || memberIdStr.isBlank()
                || tenantIdStr == null || tenantIdStr.isBlank()) {
            log.debug("[{}] Missing subjectId or tenantId — dropping", TOPIC);
            return Mono.empty();
        }
        final UUID memberId;
        try {
            memberId = UUID.fromString(memberIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("[{}] Invalid memberId in payload: {}", TOPIC, e.getMessage());
            return Mono.empty();
        }

        String[] system = AuditActor.systemActor();
        return memberService.cancelScheduledStatus(memberId, "lapsed", CANCEL_REASON,
                        system[0], system[1])
            .doOnNext(saved -> log.info(
                    "[{}] Processed clear for member {} (scheduled_status now={})",
                    TOPIC, memberId, saved.getScheduledStatus()))
            .then()
            .contextWrite(Context.of(TenantContext.KEY, tenantIdStr));
    }

    private static String fullCauseChain(Throwable e) {
        StringBuilder sb = new StringBuilder(e.toString());
        Throwable cause = e.getCause();
        while (cause != null && cause != e) {
            sb.append(" -> ").append(cause);
            cause = cause.getCause();
        }
        return sb.toString();
    }
}
