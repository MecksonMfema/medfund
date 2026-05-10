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
    @Operation(summary = "List all scheduled job configurations")
    public Flux<ScheduledJobConfig> findAll() {
        return scheduledJobService.findAll();
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
    @Operation(summary = "Create a new scheduled job config")
    public Mono<ScheduledJobConfig> create(
            @RequestParam String jobType,
            @RequestParam String name,
            @RequestParam String cronExpression,
            @RequestParam(required = false) String settings,
            Principal principal) {
        return scheduledJobService.create(jobType, name, cronExpression, settings, principal.getName());
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
    @Operation(summary = "Seed default job configs for the current tenant")
    public Mono<Void> seedDefaults(Principal principal) {
        return scheduledJobService.seedDefaults(principal.getName());
    }

    @GetMapping("/{id}/runs")
    @Operation(summary = "List recent runs for a scheduled job",
        description = "Returns the latest job executions ordered newest-first. Used by the platform-admin job monitor to surface success/failure history and per-run errors.")
    public Flux<ScheduledJobRun> listRuns(@PathVariable UUID id,
                                           @RequestParam(required = false, defaultValue = "50") int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        return runRepository.findRecent(id, capped);
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
