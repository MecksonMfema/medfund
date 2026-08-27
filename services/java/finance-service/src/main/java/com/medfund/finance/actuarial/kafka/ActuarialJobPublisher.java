package com.medfund.finance.actuarial.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.actuarial.ActuarialJobRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

/**
 * Publishes a shaped actuarial job payload to
 * {@code medfund.actuarial.job-requested} for the ai-service compute layer.
 * Payload size is gated (warn ≥ 800KB, reject ≥ 900KB) per Grill note 7 —
 * the Kafka default max-message ceiling is 1MB, and JSON overhead means
 * the safe threshold sits well below the raw limit.
 *
 * <p>Serialisation failures propagate as {@link IllegalStateException} — the
 * caller ({@code ActuarialJobService}) must roll the job row's status to
 * {@code failed} if publish never lands, since without publish there is
 * nothing to complete the row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActuarialJobPublisher {

    static final String TOPIC = "medfund.actuarial.job-requested";
    static final int SIZE_WARN_KB = 800;
    static final int SIZE_REJECT_KB = 900;

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public Mono<Void> publish(ActuarialJobRequestedEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return Mono.error(new IllegalStateException(
                    "Failed to serialise ActuarialJobRequestedEvent for job " + event.jobId(), e));
        }
        int sizeKb = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length / 1024;
        if (sizeKb >= SIZE_REJECT_KB) {
            return Mono.error(new IllegalStateException(
                    "job-requested payload too large (" + sizeKb + "KB) for job " + event.jobId()
                            + "; narrow the period or coarsen the grain"));
        }
        if (sizeKb >= SIZE_WARN_KB) {
            log.warn("[actuarial-publisher] job {} payload size {}KB approaching Kafka ceiling",
                    event.jobId(), sizeKb);
        }
        var record = new ProducerRecord<>(TOPIC, event.jobId().toString(), payload);
        return kafkaSender.send(Mono.just(SenderRecord.create(record, event.jobId())))
                .next()
                .then()
                .doOnError(e -> log.error("[actuarial-publisher] failed to publish job {}: {}",
                        event.jobId(), e.getMessage(), e));
    }
}
