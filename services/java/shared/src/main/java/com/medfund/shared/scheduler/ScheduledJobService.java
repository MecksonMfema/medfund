package com.medfund.shared.scheduler;

import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Service
public class ScheduledJobService {

    private final ScheduledJobRepository jobRepository;
    private final AuditPublisher auditPublisher;

    public ScheduledJobService(ScheduledJobRepository jobRepository, AuditPublisher auditPublisher) {
        this.jobRepository = jobRepository;
        this.auditPublisher = auditPublisher;
    }

    public Flux<ScheduledJobConfig> findAll() {
        return jobRepository.findAllOrderByJobType();
    }

    public Mono<ScheduledJobConfig> findById(UUID id) {
        return jobRepository.findById(id);
    }

    public Mono<ScheduledJobConfig> findByJobType(String jobType) {
        return jobRepository.findByJobType(jobType);
    }

    @Transactional
    public Mono<ScheduledJobConfig> create(String jobType, String name, String cronExpression,
                                            String settings, String actorId) {
        var config = new ScheduledJobConfig();
        config.setId(UUID.randomUUID());
        config.setJobType(jobType);
        config.setName(name);
        config.setCronExpression(cronExpression);
        config.setIsEnabled(true);
        config.setSettings(settings != null ? settings : "{}");
        config.setNextExecutionAt(JobDispatcher.calculateNextExecution(cronExpression));
        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());
        config.setCreatedBy(UUID.fromString(actorId));

        return jobRepository.save(config)
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "ScheduledJobConfig", saved.getId().toString(), saved.getName(),
                    "CREATE", actorId, null,
                    null,
                    Map.<String, Object>of("jobType", saved.getJobType(), "cronExpression", saved.getCronExpression()),
                    new String[]{"jobType", "cronExpression", "settings"},
                    UUID.randomUUID().toString()
                );
                return auditPublisher.publish(event).thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<ScheduledJobConfig> update(UUID id, String cronExpression, String settings,
                                            Boolean isEnabled, String actorId) {
        return jobRepository.findById(id)
            .flatMap(existing -> {
                if (cronExpression != null) {
                    existing.setCronExpression(cronExpression);
                    existing.setNextExecutionAt(JobDispatcher.calculateNextExecution(cronExpression));
                }
                if (settings != null) existing.setSettings(settings);
                if (isEnabled != null) existing.setIsEnabled(isEnabled);
                existing.setUpdatedAt(Instant.now());

                return jobRepository.save(existing)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        var event = AuditEvent.create(
                            tenantId != null ? tenantId : "unknown",
                            "ScheduledJobConfig", saved.getId().toString(), saved.getName(),
                            "UPDATE", actorId, null,
                            null,
                            Map.<String, Object>of("cronExpression", saved.getCronExpression(),
                                   "isEnabled", String.valueOf(saved.getIsEnabled())),
                            new String[]{"cronExpression", "settings", "isEnabled"},
                            UUID.randomUUID().toString()
                        );
                        return auditPublisher.publish(event).thenReturn(saved);
                    }));
            });
    }

    @Transactional
    public Mono<ScheduledJobConfig> enable(UUID id, String actorId) {
        return update(id, null, null, true, actorId);
    }

    @Transactional
    public Mono<ScheduledJobConfig> disable(UUID id, String actorId) {
        return update(id, null, null, false, actorId);
    }

    /**
     * Seed default job configs for a new tenant.
     * Called during tenant provisioning.
     */
    @Transactional
    public Mono<Void> seedDefaults(String actorId) {
        return Flux.just(
            createDefault(JobType.BILLING_CYCLE, "Monthly Billing Cycle",
                "0 0 10 15 * *", // 15th of month at 10am
                "{\"billingDayOfMonth\":15,\"advanceDays\":0}"),
            createDefault(JobType.OVERDUE_CHECK, "Daily Overdue Check",
                "0 0 2 * * *", // daily 2am
                "{\"gracePeriodDays\":30}"),
            createDefault(JobType.PAYMENT_RUN, "Weekly Payment Run",
                "0 0 6 * * MON", // Monday 6am
                "{\"autoExecuteAfterHours\":24,\"batchSize\":100}"),
            createDefault(JobType.AGE_PROCESSING, "Daily Age Processing",
                "0 0 4 * * *", // daily 4am
                "{\"maxDependantAge\":21,\"maxStudentAge\":26}"),
            createDefault(JobType.PRE_AUTH_EXPIRY, "Daily Pre-Auth Expiry",
                "0 0 3 * * *", // daily 3am
                "{}"),
            createDefault(JobType.TARIFF_ACTIVATION, "Daily Tariff Activation",
                "0 0 0 * * *", // daily midnight
                "{}")
        ).flatMap(config -> jobRepository.save(config))
        .then();
    }

    private ScheduledJobConfig createDefault(JobType type, String name, String cron, String settings) {
        var config = new ScheduledJobConfig();
        config.setId(UUID.randomUUID());
        config.setJobType(type.name());
        config.setName(name);
        config.setCronExpression(cron);
        config.setIsEnabled(true);
        config.setSettings(settings);
        config.setNextExecutionAt(JobDispatcher.calculateNextExecution(cron));
        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());
        return config;
    }
}
