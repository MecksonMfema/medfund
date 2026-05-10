package com.medfund.shared.scheduler;

import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

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
     * "due now" check and writes a run row with trigger_kind = 'manual'.
     */
    public Mono<ScheduledJobRun> runNow(ScheduledJobConfig config, UUID actorId) {
        return runIfExecutable(config, "manual", actorId)
            .switchIfEmpty(Mono.error(new IllegalStateException(
                "No executor in this service handles job type " + config.getJobType())));
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
                Mono<ScheduledJobRun> executed = executor.execute(executorTenantArg, config.getSettings())
                    .then(Mono.defer(() -> markSuccess(persisted, startMs)))
                    .onErrorResume(e -> markFailure(persisted, startMs, e));
                return configTenantId != null
                    ? executed.contextWrite(ctx -> TenantContext.put(ctx, configTenantId.toString()))
                    : executed;
            })
            .flatMap(finalRun -> updateExecutionTime(config).thenReturn(finalRun));
    }

    private Mono<ScheduledJobRun> markSuccess(ScheduledJobRun run, long startMs) {
        run.setStatus("SUCCESS");
        run.setEndedAt(Instant.now());
        run.setDurationMs(System.currentTimeMillis() - startMs);
        log.info("Job completed: configId={} durationMs={}", run.getConfigId(), run.getDurationMs());
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
