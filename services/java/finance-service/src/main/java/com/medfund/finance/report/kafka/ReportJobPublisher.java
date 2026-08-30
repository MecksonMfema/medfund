package com.medfund.finance.report.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.shared.report.ReportJobRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

/**
 * Publishes a shaped report job payload to {@code medfund.report.job-requested}
 * (canonical, Phase 15 §1). The rename Phase B cutover (§22) removed the
 * legacy {@code medfund.actuarial.job-requested} dual-write — every consumer
 * has migrated to the canonical topic; §23 Phase C deletes the legacy topic.
 *
 * <p>Payload size is gated (warn ≥ 800KB, reject ≥ 900KB) per Grill note 7 —
 * the Kafka default max-message ceiling is 1MB, and JSON overhead means the
 * safe threshold sits well below the raw limit. Phase §10 introduces a MinIO
 * fallback for oversize payloads; until then, oversize submissions are refused
 * up front.
 *
 * <p>Serialisation failures propagate as {@link IllegalStateException} — the
 * caller must roll the job row's status to {@code failed} if publish never
 * lands, since without publish there is nothing to complete the row.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportJobPublisher {

    public static final String TOPIC = "medfund.report.job-requested";
    static final int SIZE_WARN_KB = 800;
    static final int SIZE_REJECT_KB = 900;

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public Mono<Void> publish(ReportJobRequestedEvent event) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            return Mono.error(new IllegalStateException(
                    "Failed to serialise ReportJobRequestedEvent for job " + event.jobId(), e));
        }
        int sizeKb = payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length / 1024;
        if (sizeKb >= SIZE_REJECT_KB) {
            return Mono.error(new IllegalStateException(
                    "job-requested payload too large (" + sizeKb + "KB) for job " + event.jobId()
                            + "; narrow the period or coarsen the grain"));
        }
        if (sizeKb >= SIZE_WARN_KB) {
            log.warn("[report-publisher] job {} payload size {}KB approaching Kafka ceiling",
                    event.jobId(), sizeKb);
        }
        String key = event.jobId().toString();
        var canonical = new ProducerRecord<>(TOPIC, key, payload);

        return kafkaSender
                .send(Mono.just(SenderRecord.create(canonical, event.jobId())))
                .next()
                .then()
                .doOnError(e -> log.error("[report-publisher] failed to publish job {} to {}: {}",
                        event.jobId(), TOPIC, e.getMessage(), e));
    }
}
