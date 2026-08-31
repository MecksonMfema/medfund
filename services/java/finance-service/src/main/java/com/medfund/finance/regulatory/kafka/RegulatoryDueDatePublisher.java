package com.medfund.finance.regulatory.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.report.RegulatoryDueDateApproachingEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

/**
 * Kafka producer for {@code medfund.regulatory.due-date-approaching}
 * (Phase 16 §0 REG20). Publishes one event per (tenant, report_key,
 * event_tier) tick from {@code RegulatoryDueDateScanner}. Partition key
 * is the tenant so all events for one tenant land on the same partition
 * — the Go dispatcher's dedupe / throttle logic stays per-partition-safe.
 *
 * <p>Publish is best-effort: a lost due-date reminder is not worth
 * blocking the daily cron. Errors log with the full cause chain per
 * {@code bug_reactor_kafka_ack_swallow} — but the scanner-side {@code
 * .doOnSuccess}-only ack pattern lives in the scanner, not here.
 */
@Slf4j
@Component
public class RegulatoryDueDatePublisher {

    public static final String TOPIC = RegulatoryDueDateApproachingEvent.TOPIC;

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public RegulatoryDueDatePublisher(KafkaSender<String, String> kafkaSender,
                                      ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    public Mono<Void> publish(RegulatoryDueDateApproachingEvent event) {
        if (event == null || event.tenantId() == null) {
            log.warn("[reg-due-date] null event or tenant — dropping");
            return Mono.empty();
        }
        String tenantKey = event.tenantId().toString();
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            log.error("[reg-due-date] serialise failed tenant={} key={} tier={}: {}",
                    tenantKey, event.reportKey(), event.eventTier(), e.getMessage(), e);
            return Mono.empty();
        }
        var record = new ProducerRecord<>(TOPIC, tenantKey, payload);
        return kafkaSender.send(Mono.just(SenderRecord.create(record, tenantKey)))
                .next()
                .then()
                .doOnError(e -> log.error(
                        "[reg-due-date] publish failed tenant={} key={} tier={}: {}",
                        tenantKey, event.reportKey(), event.eventTier(), e.getMessage(), e))
                .onErrorResume(e -> Mono.empty());
    }
}
