package com.medfund.shared.scheduler;

import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JobDispatcher {

    private static final Logger log = LoggerFactory.getLogger(JobDispatcher.class);
    private static final int MAX_ERROR_LENGTH = 4000;

    private final ScheduledJobRepository jobRepository;
    private final ScheduledJobRunRepository runRepository;
    private final Map<JobType, JobExecutor> executors;

    public JobDispatcher(ScheduledJobRepository jobRepository,
                         ScheduledJobRunRepository runRepository,
                         List<JobExecutor> executorList) {
        this.jobRepository = jobRepository;
        this.runRepository = runRepository;
        this.executors = executorList.stream()
            .collect(Collectors.toMap(JobExecutor::getJobType, Function.identity()));
        log.info("JobDispatcher initialized with {} executors: {}", executors.size(),
            executors.keySet().stream().map(Enum::name).collect(Collectors.joining(", ")));
    }

    /**
     * Polls every 5 minutes for due jobs across all tenants.
     * Each service only has executors for its own job types, so jobs without
     * a matching executor in this service are silently skipped.
     */
    @Scheduled(fixedRate = 300000) // 5 minutes
    public void dispatch() {
        try {
            jobRepository.findDueJobs(Instant.now())
                .flatMap(config -> runIfExecutable(config, "schedule", null))
                .then()
                .block();
        } catch (Exception e) {
            log.error("JobDispatcher cycle failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Manual execution path used by the platform-admin job monitor. Skips the
     * "due now" check and writes a run row with trigger_kind = 'manual'. The
     * returned Mono only completes when the executor finishes — use
     * {@link #runNowAsync} when the caller can't wait.
     */
    public Mono<ScheduledJobRun> runNow(ScheduledJobConfig config, UUID actorId) {
        return runIfExecutable(config, "manual", actorId)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "No executor in this service handles job type " + config.getJobType())));
    }

    /**
     * Fire-and-forget manual run. Persists the RUNNING row synchronously
     * so the caller has a stable runId to return immediately, then kicks the
     * executor on {@link Schedulers#boundedElastic()} and emits the RUNNING
     * row right away. The UI polls
     * {@code /api/v1/scheduled-jobs/{configId}/runs} for completion +
     * result_payload. Errors thrown by the executor land on the same run row
     * via {@code markFailure} — exactly like the synchronous path.
     */
    public Mono<ScheduledJobRun> runNowAsync(ScheduledJobConfig config, UUID actorId) {
        JobType jobType;
        try {
            jobType = JobType.valueOf(config.getJobType());
        } catch (IllegalArgumentException e) {
            return Mono.error(new IllegalStateException("Unknown job type: " + config.getJobType()));
        }
        JobExecutor executor = executors.get(jobType);
        if (executor == null) {
            return Mono.error(new IllegalStateException(
                "No executor in this service handles job type " + config.getJobType()));
        }

        UUID configTenantId = config.getTenantId();
        String executorTenantArg = configTenantId != null ? configTenantId.toString() : "global";

        var runRecord = new ScheduledJobRun();
        runRecord.setConfigId(config.getId());
        runRecord.setTenantId(configTenantId);
        runRecord.setStartedAt(Instant.now());
        runRecord.setStatus("RUNNING");
        runRecord.setTriggerKind("manual");
        runRecord.setTriggeredBy(actorId);

        return runRepository.save(runRecord)
            .doOnNext(persisted -> {
                long startMs = System.currentTimeMillis();
                Mono<String> payloadMono = (executor instanceof ResultfulJobExecutor resultful)
                    ? resultful.executeAndCapture(executorTenantArg, config.getSettings())
                    : executor.execute(executorTenantArg, config.getSettings()).then(Mono.<String>empty());

                Mono<ScheduledJobRun> work = payloadMono
                    .defaultIfEmpty("")
                    .flatMap(payload -> markSuccess(persisted, startMs, payload.isEmpty() ? null : payload))
                    .onErrorResume(e -> markFailure(persisted, startMs, e))
                    .flatMap(finalRun -> updateExecutionTime(config).thenReturn(finalRun));

                Mono<ScheduledJobRun> ctxWork = configTenantId != null
                    ? work.contextWrite(Context.of(TenantContext.KEY, configTenantId.toString()))
                    : work;

                ctxWork.subscribeOn(Schedulers.boundedElastic())
                    .subscribe(
                        ignored -> {},
                        err -> log.error("Async job execution failed outside run-row capture: {}",
                            err.getMessage(), err));
            });
    }

    private Mono<ScheduledJobRun> runIfExecutable(ScheduledJobConfig config, String triggerKind, UUID actorId) {
        JobType jobType;
        try {
            jobType = JobType.valueOf(config.getJobType());
        } catch (IllegalArgumentException e) {
            log.debug("Unknown job type: {}, skipping", config.getJobType());
            return Mono.empty();
        }

        JobExecutor executor = executors.get(jobType);
        if (executor == null) {
            // This service doesn't handle this job type — skip silently for
            // scheduled ticks; the manual caller maps the empty into an error.
            return Mono.empty();
        }

        log.info("Executing job: type={}, name={}, configId={}, trigger={}",
            config.getJobType(), config.getName(), config.getId(), triggerKind);

        var runRecord = new ScheduledJobRun();
        // id deliberately not set — Spring Data R2DBC's save() takes the
        // UPDATE branch when @Id is non-null. Postgres generates the id
        // via the column's DEFAULT gen_random_uuid() and save() returns
        // the populated entity.
        runRecord.setConfigId(config.getId());
        runRecord.setTenantId(config.getTenantId());
        runRecord.setStartedAt(Instant.now());
        runRecord.setStatus("RUNNING");
        runRecord.setTriggerKind(triggerKind);
        runRecord.setTriggeredBy(actorId);

        // The executor's downstream queries hit tenant-scoped tables, so we
        // must write the tenant id into Reactor context before invoking it.
        // For platform-global jobs (config.tenantId == null) the context
        // stays unset and the connection factory falls through to public.
        UUID configTenantId = config.getTenantId();
        String executorTenantArg = configTenantId != null ? configTenantId.toString() : "global";

        return runRepository.save(runRecord)
            .flatMap(persisted -> {
                long startMs = System.currentTimeMillis();
                // ResultfulJobExecutor hands back a JSON payload we persist
                // onto the run row; plain JobExecutor returns Mono<Void> so
                // we adapt it into an empty Mono<String> and let the run
                // succeed with result_payload = null.
                Mono<String> payloadMono = (executor instanceof ResultfulJobExecutor resultful)
                    ? resultful.executeAndCapture(executorTenantArg, config.getSettings())
                    : executor.execute(executorTenantArg, config.getSettings()).then(Mono.<String>empty());

                Mono<ScheduledJobRun> executed = payloadMono
                    .defaultIfEmpty("")
                    .flatMap(payload -> markSuccess(persisted, startMs, payload.isEmpty() ? null : payload))
                    .onErrorResume(e -> markFailure(persisted, startMs, e));
                return configTenantId != null
                    ? executed.contextWrite(ctx -> TenantContext.put(ctx, configTenantId.toString()))
                    : executed;
            })
            .flatMap(finalRun -> updateExecutionTime(config).thenReturn(finalRun));
    }

    private Mono<ScheduledJobRun> markSuccess(ScheduledJobRun run, long startMs, String resultPayload) {
        run.setStatus("SUCCESS");
        run.setEndedAt(Instant.now());
        run.setDurationMs(System.currentTimeMillis() - startMs);
        run.setResultPayload(resultPayload);
        log.info("Job completed: configId={} durationMs={} payloadBytes={}",
            run.getConfigId(), run.getDurationMs(), resultPayload != null ? resultPayload.length() : 0);
        return runRepository.save(run);
    }

    private Mono<ScheduledJobRun> markFailure(ScheduledJobRun run, long startMs, Throwable e) {
        run.setStatus("FAILED");
        run.setEndedAt(Instant.now());
        run.setDurationMs(System.currentTimeMillis() - startMs);
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        run.setErrorMessage(message.length() > MAX_ERROR_LENGTH ? message.substring(0, MAX_ERROR_LENGTH) : message);
        log.error("Job failed: configId={} error={}", run.getConfigId(), message);
        return runRepository.save(run);
    }

    private Mono<Void> updateExecutionTime(ScheduledJobConfig config) {
        config.setLastExecutedAt(Instant.now());
        config.setNextExecutionAt(calculateNextExecution(config.getCronExpression()));
        return jobRepository.save(config).then();
    }

    /**
     * Calculate next execution time from a cron expression.
     */
    public static Instant calculateNextExecution(String cronExpression) {
        try {
            CronExpression cron = CronExpression.parse(cronExpression);
            LocalDateTime next = cron.next(LocalDateTime.now());
            return next != null ? next.toInstant(ZoneOffset.UTC) : null;
        } catch (Exception e) {
            // Fallback: 24 hours from now
            return Instant.now().plusSeconds(86400);
        }
    }
}
