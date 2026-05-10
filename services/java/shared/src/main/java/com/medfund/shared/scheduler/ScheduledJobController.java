package com.medfund.shared.scheduler;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scheduled-jobs")
@Tag(name = "Scheduled Jobs", description = "Tenant-configurable scheduled job management")
@SecurityRequirement(name = "bearer-jwt")
public class ScheduledJobController {

    private final ScheduledJobService scheduledJobService;
    private final ScheduledJobRunRepository runRepository;
    private final JobDispatcher jobDispatcher;

    public ScheduledJobController(ScheduledJobService scheduledJobService,
                                   ScheduledJobRunRepository runRepository,
                                   JobDispatcher jobDispatcher) {
        this.scheduledJobService = scheduledJobService;
        this.runRepository = runRepository;
        this.jobDispatcher = jobDispatcher;
    }

    @GetMapping
    @Operation(summary = "List scheduled job configurations",
        description = "Without a tenantId filter, returns every config across every tenant (platform-admin view). " +
                      "With a tenantId, scopes to that tenant. Each row carries its tenantId.")
    public Flux<ScheduledJobConfig> findAll(@RequestParam(required = false) UUID tenantId) {
        return tenantId != null
            ? scheduledJobService.findAllByTenant(tenantId)
            : scheduledJobService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get job config by ID")
    public Mono<ScheduledJobConfig> findById(@PathVariable UUID id) {
        return scheduledJobService.findById(id);
    }

    @GetMapping("/types")
    @Operation(summary = "List available job types")
    public Flux<JobTypeInfo> listJobTypes() {
        return Flux.fromArray(JobType.values())
            .map(jt -> new JobTypeInfo(jt.name(), jt.getDisplayName(), jt.getDescription()));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a new scheduled job config",
        description = "Pass tenantId to bind the job to a tenant. Omit it to create a platform-global job that runs " +
                      "without tenant context (e.g. cross-tenant onboarding sweeps).")
    public Mono<ScheduledJobConfig> create(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam String jobType,
            @RequestParam String name,
            @RequestParam String cronExpression,
            @RequestParam(required = false) String settings,
            Principal principal) {
        return scheduledJobService.create(tenantId, jobType, name, cronExpression, settings, principal.getName());
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update job config (schedule, settings, enabled)")
    public Mono<ScheduledJobConfig> update(
            @PathVariable UUID id,
            @RequestParam(required = false) String cronExpression,
            @RequestParam(required = false) String settings,
            @RequestParam(required = false) Boolean isEnabled,
            Principal principal) {
        return scheduledJobService.update(id, cronExpression, settings, isEnabled, principal.getName());
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Enable a scheduled job")
    public Mono<ScheduledJobConfig> enable(@PathVariable UUID id, Principal principal) {
        return scheduledJobService.enable(id, principal.getName());
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable a scheduled job")
    public Mono<ScheduledJobConfig> disable(@PathVariable UUID id, Principal principal) {
        return scheduledJobService.disable(id, principal.getName());
    }

    @PostMapping("/seed-defaults")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Seed default job configs for a tenant",
        description = "Pass tenantId to seed for that tenant; if omitted, the seeded jobs are platform-global.")
    public Mono<Void> seedDefaults(@RequestParam(required = false) UUID tenantId, Principal principal) {
        return scheduledJobService.seedDefaults(tenantId, principal.getName());
    }

    @GetMapping("/{id}/runs")
    @Operation(summary = "List recent runs for a scheduled job",
        description = "Returns the latest job executions ordered newest-first. Each row carries its tenantId. " +
                      "Pass tenantId as a query param to scope results to a single tenant; omit for cross-tenant view.")
    public Flux<ScheduledJobRun> listRuns(@PathVariable UUID id,
                                           @RequestParam(required = false, defaultValue = "50") int limit,
                                           @RequestParam(required = false) UUID tenantId) {
        int capped = Math.max(1, Math.min(limit, 200));
        return tenantId != null
            ? runRepository.findRecentForTenant(id, tenantId, capped)
            : runRepository.findRecent(id, capped);
    }

    @PostMapping("/{id}/run-now")
    @Operation(summary = "Manually trigger a scheduled job",
        description = "Bypasses the cron schedule and runs the job immediately. Records a run row with trigger_kind='manual'. Errors if no executor in this service handles the job's type.")
    public Mono<ScheduledJobRun> runNow(@PathVariable UUID id, Principal principal) {
        UUID actorId = parseActor(principal);
        return scheduledJobService.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheduled job not found: " + id)))
            .flatMap(config -> jobDispatcher.runNow(config, actorId));
    }

    private static UUID parseActor(Principal principal) {
        if (principal == null || principal.getName() == null) return null;
        try { return UUID.fromString(principal.getName()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public record JobTypeInfo(String type, String displayName, String description) {}
}
