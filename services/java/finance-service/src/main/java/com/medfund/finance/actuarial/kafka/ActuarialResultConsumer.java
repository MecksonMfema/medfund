package com.medfund.finance.actuarial.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.actuarial.repository.ActuarialReportJobRepository;
import com.medfund.shared.actuarial.ActuarialJobCompletedEvent;
import com.medfund.shared.tenant.TenantContext;
import io.r2dbc.postgresql.codec.Json;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.context.Context;

import java.time.OffsetDateTime;
import java.util.Collections;

/**
 * Sole subscriber on {@code medfund.actuarial.job-completed}. Fetches the
 * matching {@code actuarial_report_job} row, enforces the Rule 2 tenant
 * match against the payload's {@code tenantId}, and writes the terminal
 * status / result / error. The V141 append-only trigger enforces that a
 * completed / failed row cannot be re-written, so a duplicate delivery
 * from ai-service bounces off the DB instead of clobbering.
 *
 * <p>Offset ack via {@code .doOnSuccess} — {@code bug_reactor_kafka_ack_swallow}
 * says {@code .doOnTerminate} would fire on error and silently drop failed
 * records. Deserialisation errors (a malformed envelope) still ack because
 * retrying that specific record forever poisons the whole consumer group;
 * the record is logged with the full payload for post-mortem.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ActuarialResultConsumer {

    static final String TOPIC = "medfund.actuarial.job-completed";

    private final ReceiverOptions<String, String> receiverOptions;
    private final ActuarialReportJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> processRecord(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .doOnError(e -> log.error(
                                "[actuarial-consumer] failed to persist result for record (payload={}): ",
                                record.value(), e))
                        .onErrorResume(e -> {
                            // Malformed envelope / unknown job / tenant mismatch — ack so we don't
                            // block the whole group on a poison-pill. The error path already logs
                            // the full payload for a manual re-drive.
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("[actuarial-consumer] receiver error: ", e))
                .retry()
                .subscribe();
    }

    /** Package-private for direct-invocation unit tests — the {@link #consume()}
     *  loop is only exercised by the IT. */
    Mono<Void> processRecord(String value) {
        return Mono.fromCallable(() -> objectMapper.readValue(value, ActuarialJobCompletedEvent.class))
                .flatMap(this::applyToJob);
    }

    private Mono<Void> applyToJob(ActuarialJobCompletedEvent event) {
        return jobRepository.findById(event.jobId())
                .switchIfEmpty(Mono.error(new IllegalStateException(
                        "Unknown actuarial_report_job.job_id from job-completed event: " + event.jobId())))
                .flatMap(job -> {
                    if (!job.getTenantId().equals(event.tenantId())) {
                        return Mono.error(new IllegalStateException(
                                "Tenant mismatch on job-completed event: job=" + job.getTenantId()
                                        + " event=" + event.tenantId() + " jobId=" + event.jobId()));
                    }
                    job.setStatus(event.status());
                    if (event.resultJson() != null) {
                        try {
                            job.setResultJson(Json.of(objectMapper.writeValueAsString(event.resultJson())));
                        } catch (Exception e) {
                            return Mono.error(new IllegalStateException(
                                    "Failed to serialise resultJson for job " + event.jobId(), e));
                        }
                    }
                    if (event.errorMessage() != null) {
                        job.setErrorMessage(event.errorMessage());
                    }
                    job.setCompletedAt(OffsetDateTime.now());
                    return jobRepository.save(job)
                            .contextWrite(Context.of(TenantContext.KEY, job.getTenantId().toString()))
                            .then();
                });
    }
}
