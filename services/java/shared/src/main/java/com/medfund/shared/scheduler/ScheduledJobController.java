package com.medfund.shared.scheduler;

import com.medfund.shared.audit.AuditActor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
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
    public Flux<ScheduledJobConfig> findAll(@RequestParam(name = "tenantId", required = false) UUID tenantId) {
        return tenantId != null
            ? scheduledJobService.findAllByTenant(tenantId)
            : scheduledJobService.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get job config by ID")
    public Mono<ScheduledJobConfig> findById(@PathVariable("id") UUID id) {
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
            @RequestParam(name = "tenantId", required = false) UUID tenantId,
            @RequestParam(name = "jobType") String jobType,
            @RequestParam(name = "name") String name,
            @RequestParam(name = "cronExpression") String cronExpression,
            @RequestParam(name = "settings", required = false) String settings,
            @AuthenticationPrincipal Jwt jwt) {
        return scheduledJobService.create(tenantId, jobType, name, cronExpression, settings,
                AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Update job config (schedule, settings, enabled)")
    public Mono<ScheduledJobConfig> update(
            @PathVariable("id") UUID id,
            @RequestParam(name = "cronExpression", required = false) String cronExpression,
            @RequestParam(name = "settings", required = false) String settings,
            @RequestParam(name = "isEnabled", required = false) Boolean isEnabled,
            @AuthenticationPrincipal Jwt jwt) {
        return scheduledJobService.update(id, cronExpression, settings, isEnabled,
                AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/enable")
    @Operation(summary = "Enable a scheduled job")
    public Mono<ScheduledJobConfig> enable(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        return scheduledJobService.enable(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "Disable a scheduled job")
    public Mono<ScheduledJobConfig> disable(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        return scheduledJobService.disable(id, AuditActor.id(jwt), AuditActor.email(jwt));
    }

    @PostMapping("/seed-defaults")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Seed default job configs for a tenant",
        description = "Pass tenantId to seed for that tenant; if omitted, the seeded jobs are platform-global.")
    public Mono<Void> seedDefaults(@RequestParam(name = "tenantId", required = false) UUID tenantId,
                                    @AuthenticationPrincipal Jwt jwt) {
        return scheduledJobService.seedDefaults(tenantId, AuditActor.id(jwt));
    }

    @GetMapping("/{id}/runs")
    @Operation(summary = "List recent runs for a scheduled job",
        description = "Returns the latest job executions ordered newest-first. Each row carries its tenantId. " +
                      "Pass tenantId as a query param to scope results to a single tenant; omit for cross-tenant view.")
    public Flux<ScheduledJobRun> listRuns(@PathVariable("id") UUID id,
                                           @RequestParam(name = "limit", required = false, defaultValue = "50") int limit,
                                           @RequestParam(name = "tenantId", required = false) UUID tenantId) {
        int capped = Math.max(1, Math.min(limit, 200));
        return tenantId != null
            ? runRepository.findRecentForTenant(id, tenantId, capped)
            : runRepository.findRecent(id, capped);
    }

    @GetMapping("/runs/recent-for-me")
    @Operation(summary = "Recent jobs the caller triggered, plus anything they have running",
        description = "Powers the Angular header bell dropdown. Returns runs where triggered_by = the JWT subject " +
                      "and (status = RUNNING OR ended_at >= since). Polled every 30s. The since query param is " +
                      "ISO-8601; default is now minus 24 hours so a long-running commit started yesterday still " +
                      "surfaces. limit caps the result set (default 20, max 100).")
    public Flux<ScheduledJobRun> recentForMe(
            @RequestParam(name = "since", required = false) Instant since,
            @RequestParam(name = "limit", required = false, defaultValue = "20") int limit,
            @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = parseActorUuid(jwt);
        if (actorId == null) {
            // No UUID-shaped subject (e.g. system tokens) — nothing to show.
            return Flux.empty();
        }
        Instant cutoff = since != null ? since : Instant.now().minus(Duration.ofHours(24));
        int capped = Math.max(1, Math.min(limit, 100));
        return runRepository.findRecentForActor(actorId, cutoff, capped);
    }

    @PostMapping("/{id}/run-now")
    @Operation(summary = "Manually trigger a scheduled job",
        description = "Bypasses the cron schedule and runs the job immediately. Records a run row with trigger_kind='manual'. Errors if no executor in this service handles the job's type.")
    public Mono<ScheduledJobRun> runNow(@PathVariable("id") UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID actorId = parseActorUuid(jwt);
        return scheduledJobService.findById(id)
            .switchIfEmpty(Mono.error(new IllegalArgumentException("Scheduled job not found: " + id)))
            .flatMap(config -> jobDispatcher.runNow(config, actorId));
    }

    /**
     * {@code triggered_by} on the run row is a UUID column, so we parse the
     * JWT subject claim (Keycloak user id) into a UUID. Falls back to null
     * for the system-actor placeholder or any non-UUID subject.
     */
    private static UUID parseActorUuid(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) return null;
        try { return UUID.fromString(jwt.getSubject()); }
        catch (IllegalArgumentException e) { return null; }
    }

    public record JobTypeInfo(String type, String displayName, String description) {}
}
