package com.medfund.finance.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.entity.PaymentAdviceRecord;
import com.medfund.finance.repository.PaymentAdviceRecordRepository;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Consumes {@code medfund.notifications.advice.sent} — the delivery
 * outcome fanned out by notification-service after each payment-advice
 * email attempt. Flips {@link PaymentAdviceRecord#getStatus()} between
 * the three terminal / semi-terminal states so operators can tell at
 * a glance which advices reached their payee:
 *
 * <ul>
 *   <li>{@code SENT}          → status = {@code sent}</li>
 *   <li>{@code FAILED}        → status stays {@code generated} (a retry is scheduled) —
 *                                just audit the attempt</li>
 *   <li>{@code DEAD_LETTERED} → status = {@code failed} with the error captured
 *                                on the audit event</li>
 * </ul>
 *
 * <p>The {@code issuedAt} timestamp is not touched — it means "when the
 * advice was generated" and stays that way regardless of delivery
 * outcome. The audit event carries the moment of the status flip.</p>
 *
 * <p>The offset ack uses {@code .doOnSuccess} (never
 * {@code .doOnTerminate}) per {@code bug_reactor_kafka_ack_swallow} —
 * a failed status flip must NOT ack, otherwise Kafka at-least-once
 * turns into at-most-once for the offending record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentAdviceStatusConsumer {

    private static final String TOPIC = "medfund.notifications.advice.sent";

    private final ReceiverOptions<String, String> receiverOptions;
    private final PaymentAdviceRecordRepository adviceRepository;
    private final AuditPublisher auditPublisher;
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
                        .doOnError(e -> log.error("Failed to process advice-status event (full chain): ", e))
                        .onErrorResume(e -> Mono.empty());
                } catch (Exception e) {
                    log.error("Error deserializing advice-status event: ", e);
                    record.receiverOffset().acknowledge();
                    return Mono.empty();
                }
            })
            .doOnError(e -> log.error("Advice-status consumer error: ", e))
            .retry()
            .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String adviceIdStr = textOrNull(node, "adviceId");
            String status      = textOrNull(node, "status");
            String tenantId    = textOrNull(node, "tenantId");
            String recipient   = textOrNull(node, "recipient");
            String errorMsg    = textOrNull(node, "error");
            String attempts    = textOrNull(node, "attempts");

            if (adviceIdStr == null || status == null) {
                log.info("Skipping advice-status event with missing fields: adviceId={}, status={}",
                        adviceIdStr, status);
                return Mono.empty();
            }
            UUID adviceId;
            try {
                adviceId = UUID.fromString(adviceIdStr);
            } catch (IllegalArgumentException ex) {
                log.warn("Skipping advice-status event with non-UUID adviceId: {}", adviceIdStr);
                return Mono.empty();
            }

            Mono<Void> flow = adviceRepository.findById(adviceId)
                .switchIfEmpty(Mono.fromRunnable(() ->
                    log.warn("advice-status event for unknown advice {}, ignoring", adviceId)))
                .flatMap(record -> applyStatus(record, status.toUpperCase(),
                        recipient, errorMsg, attempts))
                .then();

            return tenantId != null && !tenantId.isBlank()
                    ? flow.contextWrite(Context.of(TenantContext.KEY, tenantId))
                    : flow;
        } catch (Exception e) {
            log.error("Failed to parse advice-status event: ", e);
            return Mono.error(e);
        }
    }

    private Mono<PaymentAdviceRecord> applyStatus(PaymentAdviceRecord record, String status,
                                                  String recipient, String errorMsg, String attempts) {
        String previousStatus = record.getStatus();
        String nextStatus;
        switch (status) {
            case "SENT":
                nextStatus = "sent";
                break;
            case "DEAD_LETTERED":
                nextStatus = "failed";
                break;
            case "FAILED":
                // Interim failure — retry is in-flight on the notification side.
                // Audit the attempt but leave status as-is so the record stays
                // eligible for the retry-succeeded SENT flip.
                return auditAttempt(record, previousStatus, previousStatus,
                        "FAILED", recipient, errorMsg, attempts)
                    .thenReturn(record);
            default:
                log.info("Ignoring advice-status event with unknown status={}", status);
                return Mono.just(record);
        }
        if (nextStatus.equals(previousStatus)) {
            // Idempotent: re-delivery of the same terminal outcome is a no-op.
            return Mono.just(record);
        }
        record.setStatus(nextStatus);
        return adviceRepository.save(record)
            .flatMap(saved -> auditAttempt(saved, previousStatus, nextStatus,
                    status, recipient, errorMsg, attempts).thenReturn(saved));
    }

    private Mono<Void> auditAttempt(PaymentAdviceRecord record, String previousStatus, String nextStatus,
                                    String outcome, String recipient, String errorMsg, String attempts) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("status", nextStatus);
            newValue.put("deliveryOutcome", outcome);
            if (recipient != null) newValue.put("recipient", recipient);
            if (errorMsg != null)  newValue.put("error", errorMsg);
            if (attempts != null)  newValue.put("attempts", attempts);
            String entityName = "Advice " + (record.getAdviceNumber() != null
                    ? record.getAdviceNumber() : record.getId().toString());
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "PaymentAdvice",
                    record.getId().toString(),
                    entityName,
                    "UPDATE",
                    AuditActor.SYSTEM_ID,
                    AuditActor.SYSTEM_EMAIL,
                    Map.of("status", previousStatus == null ? "" : previousStatus),
                    newValue,
                    new String[]{"status"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return (v == null || v.isBlank() || "null".equals(v)) ? null : v;
    }
}
