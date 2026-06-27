package com.medfund.shared.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.sender.KafkaSender;
import reactor.kafka.sender.SenderRecord;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Publishes a JobCompleted event once per terminal job state (SUCCESS or
 * FAILED) to {@code medfund.jobs.completed}. notification-service consumes
 * this to email the triggering user when a long-running job finishes; the
 * Angular header tray consumes it (indirectly, via a REST endpoint) to
 * show in-app progress.
 *
 * <p>Fire-and-forget by design — a flaky Kafka must not poison the job
 * pipeline. Errors are logged; the run itself stays committed because
 * {@link JobDispatcher} writes the SUCCESS/FAILED row before publish.
 */
@Component
public class JobEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(JobEventPublisher.class);
    private static final String TOPIC = "medfund.jobs.completed";

    private final KafkaSender<String, String> kafkaSender;
    private final ObjectMapper objectMapper;

    public JobEventPublisher(KafkaSender<String, String> kafkaSender, ObjectMapper objectMapper) {
        this.kafkaSender = kafkaSender;
        this.objectMapper = objectMapper;
    }

    /**
     * Emit a JobCompleted event for the supplied run + config. Every field
     * is optional on the wire — consumers should treat missing values as
     * "unknown" rather than reject the payload. {@code kind} is the
     * machine-readable job-type identifier (e.g. {@code BILLING_COMMIT}).
     */
    public Mono<Void> publishJobCompleted(ScheduledJobRun run, String kind) {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("event", "JOB_COMPLETED");
        payload.put("runId",        nullSafe(run.getId()));
        payload.put("configId",     nullSafe(run.getConfigId()));
        payload.put("tenantId",     nullSafe(run.getTenantId()));
        payload.put("kind",         nullSafe(kind));
        payload.put("status",       nullSafe(run.getStatus()));
        payload.put("triggerKind",  nullSafe(run.getTriggerKind()));
        payload.put("triggeredBy",  nullSafe(run.getTriggeredBy()));
        payload.put("startedAt",    nullSafe(run.getStartedAt()));
        payload.put("endedAt",      nullSafe(run.getEndedAt()));
        payload.put("durationMs",   run.getDurationMs() == null ? "" : run.getDurationMs().toString());
        payload.put("resultPayload", nullSafe(run.getResultPayload()));
        payload.put("errorMessage", nullSafe(run.getErrorMessage()));

        try {
            String json = objectMapper.writeValueAsString(payload);
            String key = run.getId() != null ? run.getId().toString() : "unknown";
            var record = new ProducerRecord<>(TOPIC, key, json);
            return kafkaSender.send(Mono.just(SenderRecord.create(record, key)))
                    .doOnError(e -> log.warn("Failed to publish JobCompleted for run {}: {}",
                            key, e.getMessage()))
                    .then();
        } catch (Exception e) {
            log.warn("Failed to serialize JobCompleted for run {}: {}", run.getId(), e.getMessage());
            return Mono.empty();
        }
    }

    private static String nullSafe(Object v) {
        return v == null ? "" : v.toString();
    }
}
