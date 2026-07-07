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

    /**
     * Returns all configs across every tenant. Used by the platform-admin
     * job monitor when no tenant filter is applied.
     */
    public Flux<ScheduledJobConfig> findAll() {
        return jobRepository.findAllOrderByJobType();
    }

    /**
     * Returns just the configs owned by the supplied tenant. Used both by
     * tenant-admin (working in their own tenant context) and by platform
     * admins who picked a tenant in the filter.
     */
    public Flux<ScheduledJobConfig> findAllByTenant(UUID tenantId) {
        return jobRepository.findAllByTenant(tenantId);
    }

    public Mono<ScheduledJobConfig> findById(UUID id) {
        return jobRepository.findById(id);
    }

    public Mono<ScheduledJobConfig> findByJobType(String jobType) {
        return jobRepository.findByJobType(jobType);
    }

    /**
     * Creates a job config for a specific tenant. {@code tenantId} can be
     * {@code null} for a platform-global job (e.g. tenant onboarding sweeps);
     * the executor receives "global" as the tenant arg in that case.
     */
    @Transactional
    public Mono<ScheduledJobConfig> create(UUID tenantId, String jobType, String name, String cronExpression,
                                            String settings, String actorId, String actorEmail) {
        var config = new ScheduledJobConfig();
        // id deliberately not set — Spring Data R2DBC's save() takes the
        // UPDATE branch when @Id is non-null, which fails for a new row.
        // Postgres generates the id via the column's DEFAULT gen_random_uuid()
        // and save() returns the populated entity.
        config.setTenantId(tenantId);
        config.setJobType(jobType);
        config.setName(name);
        config.setCronExpression(cronExpression);
        config.setIsEnabled(true);
        config.setSettings(settings != null ? settings : "{}");
        config.setNextExecutionAt(JobDispatcher.calculateNextExecution(cronExpression));
        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());
        config.setCreatedBy(parseActor(actorId));

        return jobRepository.save(config)
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String ctxTenantId = TenantContext.get(ctx);
                String auditTenant = saved.getTenantId() != null
                    ? saved.getTenantId().toString()
                    : (ctxTenantId != null ? ctxTenantId : "platform");
                var event = AuditEvent.create(
                    auditTenant,
                    "ScheduledJobConfig", saved.getId().toString(), saved.getName(),
                    "CREATE", actorId, actorEmail,
                    null,
                    Map.<String, Object>of("jobType", saved.getJobType(), "cronExpression", saved.getCronExpression()),
                    new String[]{"jobType", "cronExpression", "settings"},
                    UUID.randomUUID().toString()
                );
                return auditPublisher.publish(event).thenReturn(saved);
            }));
    }

    private static UUID parseActor(String actorId) {
        if (actorId == null || actorId.isBlank()) return null;
        try { return UUID.fromString(actorId); } catch (IllegalArgumentException e) { return null; }
    }

    /**
     * Persists a one-off, disabled config row for an ad-hoc job execution
     * (e.g. the billing wizard kicking a BILLING_PREVIEW / BILLING_COMMIT).
     * The cron expression is a never-match sentinel so the dispatcher never
     * picks it up on its own, and {@code is_enabled = false} doubles as a
     * second guard. The caller hands the saved config straight to
     * {@link JobDispatcher#runNow(ScheduledJobConfig, UUID)}.
     *
     * <p>No audit event is emitted — ad-hoc configs are an internal
     * scheduler artefact; the meaningful audit lives on the run row + the
     * domain mutations the executor performs. A row remains in
     * {@code scheduled_job_configs} after the run; a background cleanup
     * task can later sweep configs older than N days that have no
     * subsequent runs.
     */
    @Transactional
    public Mono<ScheduledJobConfig> createAdHocConfig(UUID tenantId, String jobType, String name,
                                                       String settings, String actorId) {
        var config = new ScheduledJobConfig();
        config.setTenantId(tenantId);
        config.setJobType(jobType);
        config.setName(name);
        // Sentinel cron — only the manual run-now path ever drives ad-hoc
        // configs, and the row is disabled below so JobDispatcher.findDueJobs
        // (which filters on is_enabled = TRUE) can't pick it up anyway.
        // Annual midnight Jan 1 keeps Spring's CronExpression parser happy.
        config.setCronExpression("0 0 0 1 1 *");
        config.setIsEnabled(false);
        config.setSettings(settings != null ? settings : "{}");
        config.setNextExecutionAt(null);
        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());
        config.setCreatedBy(parseActor(actorId));
        return jobRepository.save(config);
    }

    @Transactional
    public Mono<ScheduledJobConfig> update(UUID id, String cronExpression, String settings,
                                            Boolean isEnabled, String actorId, String actorEmail) {
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
                            "UPDATE", actorId, actorEmail,
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
    public Mono<ScheduledJobConfig> enable(UUID id, String actorId, String actorEmail) {
        return update(id, null, null, true, actorId, actorEmail);
    }

    @Transactional
    public Mono<ScheduledJobConfig> disable(UUID id, String actorId, String actorEmail) {
        return update(id, null, null, false, actorId, actorEmail);
    }

    /**
     * Seed default job configs for a new tenant. Called during tenant
     * provisioning. The {@code tenantId} is stamped on every row so the
     * configs are owned by that tenant in the public-schema table.
     *
     * <p>No audit event is emitted here — bulk seeding writes directly
     * via the repository — so {@code actorId} is accepted for future use
     * but not consumed.
     */
    @Transactional
    public Mono<Void> seedDefaults(UUID tenantId, String actorId) {
        return Flux.just(
            createDefault(tenantId, JobType.BILLING_CYCLE, "Monthly Billing Cycle",
                "0 0 10 15 * *", // 15th of month at 10am
                "{\"billingDayOfMonth\":15,\"advanceDays\":0}"),
            createDefault(tenantId, JobType.OVERDUE_CHECK, "Daily Overdue Check",
                "0 0 2 * * *", // daily 2am
                "{\"gracePeriodDays\":30}"),
            createDefault(tenantId, JobType.PAYMENT_RUN, "Weekly Payment Run",
                "0 0 6 * * MON", // Monday 6am
                "{\"autoExecuteAfterHours\":24,\"batchSize\":100}"),
            createDefault(tenantId, JobType.AGE_PROCESSING, "Daily Age Processing",
                "0 0 4 * * *", // daily 4am
                "{\"maxDependantAge\":21,\"maxStudentAge\":26}"),
            createDefault(tenantId, JobType.PRE_AUTH_EXPIRY, "Daily Pre-Auth Expiry",
                "0 0 3 * * *", // daily 3am
                "{}"),
            createDefault(tenantId, JobType.TARIFF_ACTIVATION, "Daily Tariff Activation",
                "0 0 0 * * *", // daily midnight
                "{}"),
            createDefault(tenantId, JobType.SCHEME_CHANGE_ROLL, "Daily Scheme Change Roll",
                "0 30 0 * * *", // daily 00:30 — after TARIFF_ACTIVATION so today's tariffs are live
                "{}")
        ).flatMap(config -> jobRepository.save(config))
        .then();
    }

    private ScheduledJobConfig createDefault(UUID tenantId, JobType type, String name, String cron, String settings) {
        var config = new ScheduledJobConfig();
        // id deliberately left null — see comment in create() above.
        config.setTenantId(tenantId);
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
