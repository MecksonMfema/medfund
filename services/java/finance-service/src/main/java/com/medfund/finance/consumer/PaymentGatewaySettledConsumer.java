package com.medfund.finance.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.entity.Payment;
import com.medfund.finance.repository.PaymentRepository;
import com.medfund.finance.service.FinanceEventPublisher;
import com.medfund.shared.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.context.Context;

import java.time.Instant;
import java.util.Collections;
import java.util.UUID;

/**
 * V075 — consumes {@code medfund.payments.gateway.settled} emitted by the
 * stubbed Go payment-gateway once it has "processed" an outbound item.
 * Payload shape per settled item:
 * <pre>
 *   { event: "PAYMENT_GATEWAY_SETTLED",
 *     itemId, paymentId, tenantId,
 *     transactionId, providerRef, status,
 *     amount, currencyCode }
 * </pre>
 *
 * <p>When {@code status == "completed"} we flip the referenced Payment
 * row to {@code paid}, stamp {@code paid_at}, and re-publish the existing
 * {@code medfund.payments.committed} event so the platform's
 * per-payment fanout stays intact.
 *
 * <p>Offset ack via {@code .doOnSuccess} only — never
 * {@code .doOnTerminate} (see {@code bug_reactor_kafka_ack_swallow}).
 * A failing flip must not ack, otherwise Kafka at-least-once turns into
 * at-most-once for the offending record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentGatewaySettledConsumer {

    private static final String TOPIC = "medfund.payments.gateway.settled";

    private final ReceiverOptions<String, String> receiverOptions;
    private final PaymentRepository paymentRepository;
    private final FinanceEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
            .receive()
            .flatMap(record ->
                processEvent(record.value())
                    .doOnSuccess(v -> record.receiverOffset().acknowledge())
                    .doOnError(e -> log.error("[gateway-settled] processing failed (offset NOT ack'd): ", e))
                    .onErrorResume(e -> Mono.empty()))
            .doOnError(e -> log.error("[gateway-settled] consumer stream error: ", e))
            .retry()
            .subscribe();
    }

    Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String status    = textOrNull(node, "status");
            String paymentId = textOrNull(node, "paymentId");
            String tenantId  = textOrNull(node, "tenantId");
            if (!"completed".equalsIgnoreCase(status) || paymentId == null || paymentId.isBlank()) {
                return Mono.empty();
            }
            UUID pid;
            try {
                pid = UUID.fromString(paymentId);
            } catch (IllegalArgumentException ex) {
                log.warn("[gateway-settled] non-UUID paymentId={} — ignoring", paymentId);
                return Mono.empty();
            }

            Mono<Void> flow = paymentRepository.findById(pid)
                .switchIfEmpty(Mono.<Payment>fromRunnable(() ->
                    log.warn("[gateway-settled] payment {} not found — ignoring", pid)))
                .flatMap(payment -> {
                    if ("paid".equalsIgnoreCase(payment.getStatus())) {
                        // Idempotent — re-delivery is a no-op.
                        return Mono.<Void>empty();
                    }
                    payment.setStatus("paid");
                    payment.setPaidAt(Instant.now());
                    return paymentRepository.save(payment)
                        .flatMap(saved -> eventPublisher.publishPaymentCommitted(
                            saved.getId().toString(),
                            saved.getProviderId() != null ? saved.getProviderId().toString() : "",
                            saved.getAmount() != null ? saved.getAmount().toPlainString() : "0",
                            saved.getCurrencyCode() != null ? saved.getCurrencyCode() : ""));
                })
                .then();
            return tenantId != null && !tenantId.isBlank()
                ? flow.contextWrite(Context.of(TenantContext.KEY, tenantId))
                : flow;
        } catch (Exception e) {
            log.error("[gateway-settled] parse failure — offset NOT ack'd: ", e);
            return Mono.error(e);
        }
    }

    private static String textOrNull(JsonNode n, String key) {
        JsonNode v = n.get(key);
        return v == null || v.isNull() ? null : v.asText();
    }
}
