package com.medfund.finance.regulatory.aml.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.report.SuspiciousTransactionEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

/**
 * Publishes {@link SuspiciousTransactionEvent} on the
 * {@code medfund.aml.suspicious-transaction} topic on every AML alert
 * workflow transition (RAISE / REVIEW / FILE / CLOSE) — Phase 22 REG8 +
 * Phase 24.
 *
 * <p>Key = {@code alertId.toString()} so all events for one alert land on
 * the same partition and are consumed in transition order by the Go
 * dispatcher (partial-order guarantee — a REVIEW never appears before its
 * RAISE for the same alert).
 *
 * <p>Publish failure surfaces as an error {@link Mono} to the caller so
 * the workflow transition can decide whether to swallow it (best-effort
 * fan-out) or fail the request. Current callers in
 * {@link com.medfund.finance.regulatory.aml.service.AmlAlertService}
 * swallow + log — the transition itself has already committed and been
 * audit-logged; a downstream fan-out drop is recoverable via replay from
 * the audit log or the {@code suspicious_transaction_alert} row itself.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SuspiciousTransactionEventPublisher {

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public Mono<Void> publish(SuspiciousTransactionEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return Mono.error(new IllegalStateException(
                    "Failed to serialise SuspiciousTransactionEvent for alert " + event.alertId(), e));
        }
        String key = event.alertId().toString();
        var record = new ProducerRecord<>(SuspiciousTransactionEvent.TOPIC, key, payload);

        return kafkaSender
                .send(Mono.just(SenderRecord.create(record, event.alertId())))
                .next()
                .then()
                .doOnSuccess(v -> log.debug(
                        "[aml-publisher] published transition {} for alert {} tenant {}",
                        event.transition(), event.alertId(), event.tenantId()))
                .doOnError(e -> log.error(
                        "[aml-publisher] failed to publish transition {} for alert {}: {}",
                        event.transition(), event.alertId(), e.getMessage(), e));
    }
}
