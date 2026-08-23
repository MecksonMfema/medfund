package com.medfund.user.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.tenant.TenantContext;
import com.medfund.user.client.TenantAutoLapseConfigClient;
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
import java.time.LocalDate;
import java.util.Collections;
import java.util.UUID;

/**
 * Consumes {@code medfund.contributions.arrears-threshold-breached}
 * (published by {@code ArrearsThresholdPublisher} in contributions-service
 * during the daily arrears sweep). Schedules a LAPSED member status
 * transition at {@code today + graceWindowDays} when the tenant has
 * opted in via {@code tenant_auto_lapse_config.enabled = TRUE}.
 *
 * <p>MVP scope: MEMBER subject type only. GROUP breach events are
 * dropped silently (a group lapse cascade requires more surgery than
 * the current phase covers).
 *
 * <p>Per {@code bug_reactor_kafka_ack_swallow}: offset ack uses
 * {@code .doOnSuccess} exclusively — {@code .doOnTerminate} would ack
 * on error too and silently drop failed events.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ArrearsBreachedConsumer {

    private static final String TOPIC = "medfund.contributions.arrears-threshold-breached";
    private static final String ARREARS_AUTO_LAPSE = "ARREARS_AUTO_LAPSE";

    private final ReceiverOptions<String, String> receiverOptions;
    private final ObjectMapper objectMapper;
    private final MemberService memberService;
    private final TenantAutoLapseConfigClient configClient;

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

    // Package-private so the unit test in the matching package can drive
    // the event-handling logic without spinning up the full Kafka receiver.
    Mono<Void> processEvent(String json) {
        JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (Exception e) {
            log.warn("[{}] Malformed JSON dropped: {}", TOPIC, e.getMessage());
            return Mono.empty();
        }
        if (!"ARREARS_THRESHOLD_BREACHED".equals(node.path("event").asText())) return Mono.empty();
        if (!"MEMBER".equals(node.path("subjectType").asText())) {
            log.debug("[{}] Ignoring subjectType='{}' — MVP handles MEMBER only",
                    TOPIC, node.path("subjectType").asText());
            return Mono.empty();
        }
        String memberIdStr = node.path("subjectId").asText();
        String tenantIdStr = node.path("tenantId").asText();
        if (memberIdStr == null || memberIdStr.isBlank()
                || tenantIdStr == null || tenantIdStr.isBlank()) {
            log.debug("[{}] Missing subjectId or tenantId — dropping", TOPIC);
            return Mono.empty();
        }
        final UUID memberId;
        final UUID tenantId;
        try {
            memberId = UUID.fromString(memberIdStr);
            tenantId = UUID.fromString(tenantIdStr);
        } catch (IllegalArgumentException e) {
            log.warn("[{}] Invalid UUID in payload: {}", TOPIC, e.getMessage());
            return Mono.empty();
        }

        String[] system = AuditActor.systemActor();
        return configClient.get(tenantId)
            .flatMap(cfg -> {
                if (!cfg.enabled()) {
                    log.debug("[{}] Auto-lapse disabled for tenant {} — skipping member {}",
                            TOPIC, tenantId, memberId);
                    return Mono.<Void>empty();
                }
                int graceDays = cfg.graceWindowDays() != null ? cfg.graceWindowDays() : 0;
                LocalDate effective = LocalDate.now().plusDays(graceDays);
                return memberService.applyOrScheduleStatus(memberId, "lapsed", effective,
                                ARREARS_AUTO_LAPSE, system[0], system[1])
                        .doOnNext(saved -> log.info(
                                "[{}] Scheduled LAPSED for member {} effective {}",
                                TOPIC, memberId, effective))
                        .then();
            })
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
