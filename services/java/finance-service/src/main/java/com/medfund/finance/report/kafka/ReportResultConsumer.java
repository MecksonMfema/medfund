package com.medfund.finance.report.kafka;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.ifrs17.service.Ifrs17JobAggregator;
import com.medfund.finance.report.repository.ReportJobRepository;
import com.medfund.shared.report.ReportJobCompletedEvent;
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
import java.util.List;

/**
 * Sole subscriber on {@code medfund.report.job-completed} (canonical,
 * Phase 15 §1). The rename Phase B cutover (§22) dropped the legacy
 * {@code medfund.actuarial.job-completed} dual-subscribe — every publisher
 * has migrated to the canonical topic; §23 Phase C deletes the legacy topic.
 * Fetches the matching {@code report_job} row, enforces the Rule 2 tenant
 * match against the payload's {@code tenantId}, and writes the terminal
 * status / result / error. The V151 append-only trigger enforces that a
 * completed / failed row cannot be re-written, so a duplicate delivery from
 * ai-service (broker retry) bounces off the DB instead of clobbering.
 *
 * <p>Offset ack via {@code .doOnSuccess} — {@code bug_reactor_kafka_ack_swallow}
 * says {@code .doOnTerminate} would fire on error and silently drop failed
 * records. Deserialisation errors (a malformed envelope) still ack because
 * retrying that specific record forever poisons the whole consumer group;
 * the record is logged with the full payload for post-mortem.
 *
 * <p>Idempotency: {@link #applyToJob(ReportJobCompletedEvent)} short-circuits
 * when the row is already in a terminal status — Kafka can still deliver the
 * same record twice under a broker retry / consumer rebalance, and the DB
 * trigger would otherwise reject the second write with a noisy exception
 * per Grill note 21.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReportResultConsumer {

    public static final String TOPIC = "medfund.report.job-completed";

    private final ReceiverOptions<String, String> receiverOptions;
    private final ReportJobRepository jobRepository;
    private final ObjectMapper objectMapper;
    private final Ifrs17JobAggregator ifrs17Aggregator;

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(List.of(TOPIC));
        KafkaReceiver.create(options)
                .receive()
                .flatMap(record -> processRecord(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .doOnError(e -> log.error(
                                "[report-consumer] failed to persist result for record (payload={}): ",
                                record.value(), e))
                        .onErrorResume(e -> {
                            // Malformed envelope / unknown job / tenant mismatch — ack so we don't
                            // block the whole group on a poison-pill. The error path already logs
                            // the full payload for a manual re-drive.
                            record.receiverOffset().acknowledge();
                            return Mono.empty();
                        }))
                .doOnError(e -> log.error("[report-consumer] receiver error: ", e))
                .retry()
                .subscribe();
    }

    /** Package-private for direct-invocation unit tests — the {@link #consume()}
     *  loop is only exercised by the IT. */
    Mono<Void> processRecord(String value) {
        return Mono.fromCallable(() -> objectMapper.readValue(value, ReportJobCompletedEvent.class))
                .flatMap(this::applyToJob);
    }

    private Mono<Void> applyToJob(ReportJobCompletedEvent event) {
        // Phase 15 §18 routing: chunk-completed events (IFRS 17 fan-out) carry
        // event.jobId == report_job_chunk.chunk_id, NOT report_job.job_id.
        // Try the parent table first (Phase 14 actuarial keeps that path); on
        // miss, fall back to the chunk table and delegate to the aggregator.
        return jobRepository.findById(event.jobId())
                .flatMap(job -> applyToParentJob(job, event))
                .switchIfEmpty(Mono.defer(() -> routeToAggregatorIfChunk(event)));
    }

    private Mono<Void> routeToAggregatorIfChunk(ReportJobCompletedEvent event) {
        return ifrs17Aggregator.findChunk(event.jobId())
                .flatMap(maybeChunk -> {
                    if (maybeChunk.isEmpty()) {
                        return Mono.error(new IllegalStateException(
                                "Unknown report_job.job_id + report_job_chunk.chunk_id from "
                                        + "job-completed event: " + event.jobId()));
                    }
                    return ifrs17Aggregator.processChunkResult(maybeChunk.get(), event);
                });
    }

    private Mono<Void> applyToParentJob(com.medfund.finance.report.entity.ReportJob job,
                                         ReportJobCompletedEvent event) {
        if (!job.getTenantId().equals(event.tenantId())) {
            return Mono.error(new IllegalStateException(
                    "Tenant mismatch on job-completed event: job=" + job.getTenantId()
                            + " event=" + event.tenantId() + " jobId=" + event.jobId()));
        }
        // Idempotency: a duplicate terminal event (broker retry / consumer
        // rebalance) would otherwise trip the V151 trigger on the second
        // UPDATE against an already-terminal row.
        if ("completed".equals(job.getStatus()) || "failed".equals(job.getStatus())) {
            log.debug("[report-consumer] duplicate terminal event for job {} — skipping",
                    event.jobId());
            return Mono.empty();
        }
        // IFRS 17 parents are aggregator-owned — the aggregator writes the
        // terminal state after all chunks land. If a completed event carries
        // event.jobId that matches an IFRS 17 *parent* (should never happen —
        // parents don't publish job-requested events), skip so we don't
        // clobber the aggregator's writes.
        if (job.getParentJobId() == null && event.reportKey() != null
                && event.reportKey().startsWith("IFRS17_")) {
            log.warn("[report-consumer] completed event with IFRS17_ report_key on parent row {} — "
                            + "skipping; aggregator owns the parent write path",
                    event.jobId());
            return Mono.empty();
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
    }
}
