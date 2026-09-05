package com.medfund.finance.report.schedule.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.report.ReportDeliveryEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

/**
 * Phase 17 §A.2 — publishes {@link ReportDeliveryEvent} to
 * {@code medfund.notification.report-delivery}. Envelope carries only a
 * MinIO ref + metadata, so no size guard is needed (bytes stay in blob store).
 *
 * <p>Uses {@code .doOnError} + {@code .doOnSuccess} for observability rather
 * than {@code .doOnTerminate} per the auto-memory {@code
 * bug_reactor_kafka_ack_swallow}: {@code .doOnTerminate} fires on error too
 * and would swallow the failure signal.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportDeliveryPublisher {

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public Mono<Void> publish(ReportDeliveryEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return Mono.error(new IllegalStateException(
                    "Failed to serialise ReportDeliveryEvent for job " + event.jobId(), e));
        }
        var record = new ProducerRecord<String, String>(
                ReportDeliveryEvent.TOPIC,
                event.tenantId().toString(),
                payload);
        return kafkaSender.send(Mono.just(SenderRecord.create(record, event.jobId())))
                .next()
                .then()
                .doOnSuccess(v -> log.debug("[report-delivery] published job={} tenant={} key={}",
                        event.jobId(), event.tenantId(), event.reportKey()))
                .doOnError(err -> log.error("[report-delivery] failed to publish job={}: {}",
                        event.jobId(), err.getMessage(), err));
    }
}
