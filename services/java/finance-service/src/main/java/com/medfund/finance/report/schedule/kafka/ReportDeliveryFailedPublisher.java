package com.medfund.finance.report.schedule.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.report.ReportDeliveryFailedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReportDeliveryFailedPublisher {

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public Mono<Void> publish(ReportDeliveryFailedEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return Mono.error(new IllegalStateException(
                    "Failed to serialise ReportDeliveryFailedEvent for job " + event.jobId(), e));
        }
        var record = new ProducerRecord<String, String>(
                ReportDeliveryFailedEvent.TOPIC,
                event.tenantId().toString(),
                payload);
        return kafkaSender.send(Mono.just(SenderRecord.create(record, event.jobId())))
                .next()
                .then()
                .doOnSuccess(v -> log.warn("[report-delivery-failed] published job={} stage={} tenant={}",
                        event.jobId(), event.failureStage(), event.tenantId()))
                .doOnError(err -> log.error("[report-delivery-failed] failed to publish job={}: {}",
                        event.jobId(), err.getMessage(), err));
    }
}
